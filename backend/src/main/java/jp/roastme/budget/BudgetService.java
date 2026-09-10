package jp.roastme.budget;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BudgetService {

  private static final UUID OWNER = UUID.fromString(
    "00000000-0000-0000-0000-000000000001"
  );
  private final JdbcTemplate db;
  private final Clock clock;
  private final long dailyLimit, monthlyLimit;

  public BudgetService(
    JdbcTemplate db,
    Clock clock,
    @Value("${app.daily-limit-micro-usd:0}") long daily,
    @Value("${app.monthly-limit-micro-usd:20000000}") long monthly
  ) {
    if (daily < 0 || monthly < 0) throw new IllegalArgumentException(
      "Negative budget"
    );
    this.db = db;
    this.clock = clock;
    this.dailyLimit = daily;
    this.monthlyLimit = monthly;
  }

  public record Snapshot(
    Long dailyLimitMicroUsd,
    long monthlyLimitMicroUsd,
    long dailyCommittedMicroUsd,
    long monthlyCommittedMicroUsd,
    String timezone
  ) {}

  public record Reservation(UUID id, String decision) {
    public boolean accepted() {
      return id != null;
    }
  }

  private void lock() {
    db.queryForObject(
      "SELECT id FROM users WHERE id=? FOR UPDATE",
      UUID.class,
      OWNER
    );
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(ZoneId.of("Asia/Tokyo")));
  }

  private long outstanding() {
    return db.queryForObject(
      "SELECT COALESCE(SUM(outstanding_micro_usd),0) FROM budget_reservations WHERE user_id=?",
      Long.class,
      OWNER
    );
  }

  private long actual(boolean month) {
    return db.queryForObject(
      "SELECT COALESCE(SUM(c.actual_micro_usd),0) FROM cost_entries c JOIN budget_reservations r ON c.reservation_id=r.id WHERE r.user_id=? AND " +
        (month ? "r.budget_month" : "r.budget_day") +
        "=?",
      Long.class,
      OWNER,
      month ? today().withDayOfMonth(1) : today()
    );
  }

  public Snapshot snapshot() {
    long held = outstanding();
    return new Snapshot(
      dailyLimit == 0 ? null : dailyLimit,
      monthlyLimit,
      Math.addExact(actual(false), held),
      Math.addExact(actual(true), held),
      "Asia/Tokyo"
    );
  }

  public Reservation reserve(UUID operation, long estimated) {
    if (estimated <= 0) throw new IllegalArgumentException(
      "Positive estimate required"
    );
    lock();
    var existing = db.queryForList(
      "SELECT id,status FROM budget_reservations WHERE operation_id=?",
      operation
    );
    if (!existing.isEmpty()) return new Reservation(
      (UUID) existing.get(0).get("id"),
      "EXISTING"
    );
    var s = snapshot();
    if (dailyLimit == 0) return new Reservation(null, "UNAVAILABLE");
    if (
      estimated > dailyLimit - s.dailyCommittedMicroUsd
    ) return new Reservation(null, "DAILY_BUDGET");
    if (
      estimated > monthlyLimit - s.monthlyCommittedMicroUsd
    ) return new Reservation(null, "MONTHLY_BUDGET");
    var slot = db.queryForMap(
      "SELECT * FROM generation_slot WHERE singleton_id=1 FOR UPDATE"
    );
    if (slot.get("operation_id") != null) return new Reservation(null, "BUSY");
    UUID id = UUID.randomUUID();
    db.update(
      "INSERT INTO budget_reservations VALUES (?,?,?,?,?,?,?,'RESERVED',?)",
      id,
      OWNER,
      operation,
      today(),
      today().withDayOfMonth(1),
      estimated,
      estimated,
      Timestamp.from(clock.instant())
    );
    db.update(
      "UPDATE generation_slot SET operation_id=? WHERE singleton_id=1",
      operation
    );
    return new Reservation(id, "ACCEPTED");
  }

  public boolean topUp(UUID reservation, long extra) {
    if (extra <= 0) throw new IllegalArgumentException(
      "Positive top-up required"
    );
    lock();
    var s = snapshot();
    if (
      dailyLimit == 0 ||
      extra > dailyLimit - s.dailyCommittedMicroUsd ||
      extra > monthlyLimit - s.monthlyCommittedMicroUsd
    ) return false;
    return (
      db.update(
        "UPDATE budget_reservations SET outstanding_micro_usd=outstanding_micro_usd+?,estimated_micro_usd=estimated_micro_usd+? WHERE id=? AND user_id=? AND status='RESERVED'",
        extra,
        extra,
        reservation,
        OWNER
      ) == 1
    );
  }

  public void settleAttempt(
    UUID reservation,
    UUID attempt,
    String stage,
    String provider,
    long allocatedEstimate,
    long actual
  ) {
    if (allocatedEstimate < 0 || actual < 0) throw new IllegalArgumentException(
      "Negative amount"
    );
    lock();
    if (
      db.queryForObject(
        "SELECT count(*) FROM cost_entries WHERE attempt_id=?",
        Integer.class,
        attempt
      ) > 0
    ) return;
    var r = db.queryForMap(
      "SELECT * FROM budget_reservations WHERE id=? AND user_id=?",
      reservation,
      OWNER
    );
    long held = ((Number) r.get("outstanding_micro_usd")).longValue();
    if (allocatedEstimate > held) throw new IllegalArgumentException(
      "Settlement exceeds held estimate"
    );
    db.update(
      "INSERT INTO cost_entries VALUES (?,?,?,?,?,?,?)",
      UUID.randomUUID(),
      reservation,
      attempt,
      stage,
      provider,
      actual,
      Timestamp.from(clock.instant())
    );
    db.update(
      "UPDATE budget_reservations SET outstanding_micro_usd=outstanding_micro_usd-? WHERE id=?",
      allocatedEstimate,
      reservation
    );
  }

  // Only invoke after definite completion/non-submission. Ambiguous charges use unknown().
  public void finishConfirmed(UUID reservation) {
    lock();
    var r = db.queryForMap(
      "SELECT operation_id FROM budget_reservations WHERE id=? AND user_id=?",
      reservation,
      OWNER
    );
    db.update(
      "UPDATE budget_reservations SET outstanding_micro_usd=0,status='SETTLED' WHERE id=?",
      reservation
    );
    release((UUID) r.get("operation_id"));
  }

  public void unknown(UUID reservation) {
    lock();
    var r = db.queryForMap(
      "SELECT operation_id FROM budget_reservations WHERE id=? AND user_id=?",
      reservation,
      OWNER
    );
    db.update(
      "UPDATE budget_reservations SET status='UNKNOWN' WHERE id=?",
      reservation
    );
    release((UUID) r.get("operation_id"));
  }

  private void release(UUID operation) {
    db.update(
      "UPDATE generation_slot SET operation_id=NULL WHERE singleton_id=1 AND operation_id=?",
      operation
    );
  }
}
