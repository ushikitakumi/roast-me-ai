package jp.roastme.media;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import jp.roastme.budget.BudgetService;
import jp.roastme.domain.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MediaPipeline {

  private static final UUID OWNER = UUID.fromString(
    "00000000-0000-0000-0000-000000000001"
  );
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final SpeechSynthesizer speech;
  private final AvatarVideoGenerator video;
  private final LocalMediaStorage storage;
  private final BudgetService budget;
  private final boolean enabled;
  private final long timeout;

  public MediaPipeline(
    JdbcTemplate db,
    PlatformTransactionManager tm,
    Clock clock,
    SpeechSynthesizer speech,
    AvatarVideoGenerator video,
    LocalMediaStorage storage,
    BudgetService budget,
    @Value("${app.stub-video:false}") boolean enabled,
    @Value("${app.video-timeout-seconds:600}") long timeout
  ) {
    this.db = db;
    this.tx = new TransactionTemplate(tm);
    this.clock = clock;
    this.speech = speech;
    this.video = video;
    this.storage = storage;
    this.budget = budget;
    this.enabled = enabled;
    this.timeout = timeout;
  }

  public boolean enabled() {
    return enabled;
  }

  private <T> T atomic(Supplier<T> run) {
    return tx.execute(s -> run.get());
  }

  private Timestamp now() {
    return Timestamp.from(clock.instant());
  }

  private void ownerLock() {
    db.queryForObject(
      "SELECT id FROM users WHERE id=? FOR UPDATE",
      UUID.class,
      OWNER
    );
  }

  public String enqueue(UUID roastId) {
    if (!enabled) return "UNAVAILABLE";
    return atomic(() -> {
      ownerLock();
      var reservation = budget.reserve(roastId, 200000);
      if (!reservation.accepted()) return reservation.decision();
      if (reservation.decision().equals("EXISTING")) return "ACCEPTED";
      db.update(
        "INSERT INTO roast_jobs(roast_id,reservation_id,status,result_deadline_at,created_at,updated_at) VALUES (?,?,'TEXT_DONE',?,?,?)",
        roastId,
        reservation.id(),
        Timestamp.from(clock.instant().plusSeconds(timeout)),
        now(),
        now()
      );
      db.update(
        "UPDATE roasts SET response_status='TEXT_DONE' WHERE id=?",
        roastId
      );
      return "ACCEPTED";
    });
  }

  private record Claim(
    UUID id,
    UUID reservation,
    String status,
    long version,
    String external,
    String audio,
    Instant deadline
  ) {}

  private boolean hidden(UUID id) {
    return db
      .queryForObject(
        "SELECT visibility FROM roasts WHERE id=?",
        String.class,
        id
      )
      .equals("SUPPRESSED");
  }

  private Claim claim(UUID id) {
    return atomic(() -> {
      ownerLock();
      var rows = db.queryForList(
        "SELECT * FROM roast_jobs WHERE roast_id=? FOR UPDATE",
        id
      );
      if (rows.isEmpty()) return null;
      var r = rows.get(0);
      String status = (String) r.get("status");
      if (
        Set.of(
          "VIDEO_DONE",
          "DEGRADED",
          "CANCELLED",
          "SUBMISSION_UNKNOWN",
          "RESULT_UNKNOWN"
        ).contains(status)
      ) return null;
      if (
        r.get("lease_expires_at") != null &&
        ((Timestamp) r.get("lease_expires_at"))
          .toInstant()
          .isAfter(clock.instant())
      ) return null;
      UUID reservation = (UUID) r.get("reservation_id");
      if (status.equals("VIDEO_SUBMITTING")) {
        end(id, reservation, "SUBMISSION_UNKNOWN", true);
        return null;
      }
      if (
        status.equals("VIDEO_SUBMITTED") &&
        !((Timestamp) r.get("result_deadline_at"))
          .toInstant()
          .isAfter(clock.instant())
      ) {
        end(id, reservation, "RESULT_UNKNOWN", true);
        return null;
      }
      if (hidden(id) && !status.equals("VIDEO_SUBMITTED")) {
        end(id, reservation, "CANCELLED", false);
        return null;
      }
      long version = ((Number) r.get("lease_version")).longValue() + 1;
      db.update(
        "UPDATE roast_jobs SET lease_owner=?,lease_version=?,lease_expires_at=?,updated_at=? WHERE roast_id=?",
        UUID.randomUUID(),
        version,
        Timestamp.from(clock.instant().plusSeconds(30)),
        now(),
        id
      );
      return new Claim(
        id,
        reservation,
        status,
        version,
        (String) r.get("external_job_id"),
        (String) r.get("audio_key"),
        ((Timestamp) r.get("result_deadline_at")).toInstant()
      );
    });
  }

  private boolean current(Claim c) {
    return (
      db.queryForObject(
        "SELECT count(*) FROM roast_jobs WHERE roast_id=? AND lease_version=? AND lease_expires_at>?",
        Integer.class,
        c.id,
        c.version,
        now()
      ) == 1
    );
  }

  private void end(UUID id, UUID reservation, String status, boolean unknown) {
    db.update(
      "UPDATE roast_jobs SET status=?,lease_expires_at=NULL,updated_at=? WHERE roast_id=?",
      status,
      now(),
      id
    );
    if (unknown) budget.unknown(reservation);
    else budget.finishConfirmed(reservation);
  }

  public void runOnce(UUID id) {
    Claim c = claim(id);
    if (c == null) return;
    try {
      if (c.status.equals("TEXT_DONE")) {
        byte[] audio = speech.synthesize(
          db.queryForObject(
            "SELECT body FROM roasts WHERE id=?",
            String.class,
            id
          )
        );
        String key = "audio/" + id + "-" + c.version + ".wav";
        storage.put(key, audio);
        atomic(() -> {
          ownerLock();
          if (!current(c)) return null;
          if (hidden(id)) {
            end(id, c.reservation, "CANCELLED", false);
            return null;
          }
          db.update(
            "UPDATE roast_jobs SET status='AUDIO_DONE',audio_key=?,lease_expires_at=NULL,updated_at=? WHERE roast_id=?",
            key,
            now(),
            id
          );
          return null;
        });
      } else if (c.status.equals("AUDIO_DONE")) {
        boolean submit = atomic(() -> {
          ownerLock();
          if (!current(c)) return false;
          if (hidden(id)) {
            end(id, c.reservation, "CANCELLED", false);
            return false;
          }
          db.update(
            "UPDATE roast_jobs SET status='VIDEO_SUBMITTING',submission_started_at=?,updated_at=? WHERE roast_id=?",
            now(),
            now(),
            id
          );
          return true;
        });
        if (!submit) return;
        String external = video.submit(id, storage.get(c.audio));
        atomic(() -> {
          ownerLock();
          if (!current(c)) {
            db.update(
              "UPDATE roast_jobs SET late_result_status=? WHERE roast_id=?",
              "LATE_ACK:" + external,
              id
            );
            return null;
          }
          db.update(
            "UPDATE roast_jobs SET status='VIDEO_SUBMITTED',external_job_id=?,lease_expires_at=NULL,updated_at=? WHERE roast_id=?",
            external,
            now(),
            id
          );
          return null;
        });
      } else if (c.status.equals("VIDEO_SUBMITTED")) {
        var state = video.status(c.external);
        if (state == AvatarVideoGenerator.VideoStatus.PROCESSING) {
          atomic(() -> {
            ownerLock();
            if (current(c)) db.update(
              "UPDATE roast_jobs SET lease_expires_at=NULL WHERE roast_id=?",
              id
            );
            return null;
          });
          return;
        }
        if (state == AvatarVideoGenerator.VideoStatus.FAILED) {
          atomic(() -> {
            ownerLock();
            if (current(c)) end(id, c.reservation, "DEGRADED", true);
            return null;
          });
          return;
        }
        byte[] bytes = video.download(c.external);
        String key = "video/" + id + "-" + c.version + ".mp4";
        storage.put(key, bytes);
        atomic(() -> {
          ownerLock();
          if (!current(c) || !c.deadline.isAfter(clock.instant())) {
            db.update(
              "UPDATE roast_jobs SET late_result_status='COMPLETE' WHERE roast_id=?",
              id
            );
            if (current(c)) end(id, c.reservation, "RESULT_UNKNOWN", true);
            return null;
          }
          // Stub has zero real cost. No external service is contacted.
          budget.settleAttempt(c.reservation, id, "VIDEO", "stub", 200000, 0);
          db.update(
            "UPDATE roast_jobs SET video_key=? WHERE roast_id=?",
            key,
            id
          );
          end(id, c.reservation, "VIDEO_DONE", false);
          return null;
        });
      }
    } catch (Exception e) {
      atomic(() -> {
        ownerLock();
        if (!current(c)) return null;
        String status = db.queryForObject(
          "SELECT status FROM roast_jobs WHERE roast_id=?",
          String.class,
          id
        );
        if (status.equals("VIDEO_SUBMITTING")) end(
          id,
          c.reservation,
          "SUBMISSION_UNKNOWN",
          true
        );
        else if (status.equals("VIDEO_SUBMITTED")) db.update(
          "UPDATE roast_jobs SET lease_expires_at=NULL,last_error_code='RETRYABLE_READ',updated_at=? WHERE roast_id=?",
          now(),
          id
        );
        else end(id, c.reservation, "DEGRADED", false);
        return null;
      });
    }
  }

  public List<UUID> runnable() {
    return db.queryForList(
      "SELECT roast_id FROM roast_jobs WHERE status IN ('TEXT_DONE','AUDIO_DONE','VIDEO_SUBMITTING','VIDEO_SUBMITTED') ORDER BY created_at LIMIT 10",
      UUID.class
    );
  }
}
