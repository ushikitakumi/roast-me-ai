package jp.roastme.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import jp.roastme.api.ApiException;
import jp.roastme.domain.TemplateRoaster;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CoreService {

  static final UUID OWNER = UUID.fromString(
    "00000000-0000-0000-0000-000000000001"
  );
  private final JdbcTemplate db;
  private final Clock clock;
  private final jp.roastme.budget.BudgetService budget;
  private final jp.roastme.media.MediaPipeline pipeline;
  private final TemplateRoaster templates = new TemplateRoaster();

  public CoreService(
    JdbcTemplate db,
    Clock clock,
    jp.roastme.budget.BudgetService budget,
    jp.roastme.media.MediaPipeline pipeline
  ) {
    this.db = db;
    this.clock = clock;
    this.budget = budget;
    this.pipeline = pipeline;
  }

  public static Map<String, Object> m(Object... pairs) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) m.put(
      (String) pairs[i],
      pairs[i + 1]
    );
    return m;
  }

  private Timestamp now() {
    return Timestamp.from(clock.instant());
  }

  private void lock() {
    db.queryForObject(
      "SELECT id FROM users WHERE id=? FOR UPDATE",
      UUID.class,
      OWNER
    );
  }

  private Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty()) throw new ApiException(
      404,
      "RESOURCE_NOT_FOUND",
      "対象が見つかりません。"
    );
    return rows.get(0);
  }

  private Map<String, Object> settings() {
    return one("SELECT * FROM user_settings WHERE user_id=?", OWNER);
  }

  private boolean paused() {
    var s = settings();
    return (
      Boolean.TRUE.equals(s.get("manual_paused")) ||
      Boolean.TRUE.equals(s.get("auto_paused"))
    );
  }

  private String intensity() {
    return (String) settings().get("roast_intensity");
  }

  private void risk(String text) {
    if (templates.seriousRisk(text)) {
      suppressPending(null, "STOPPED");
      db.update(
        "UPDATE user_settings SET auto_paused=true,resume_allowed_at=?,revision=revision+1 WHERE user_id=?",
        Timestamp.from(clock.instant().plus(Duration.ofHours(24))),
        OWNER
      );
    }
  }

  private static String text(String v, int max, boolean required) {
    String n = v == null ? "" : v.strip();
    if (
      n.codePointCount(0, n.length()) > max || (required && n.isEmpty())
    ) throw new ApiException(
      400,
      "VALIDATION_ERROR",
      "必須項目と文字数を確認してください。"
    );
    return n;
  }

  private static String allowed(String v, Set<String> choices) {
    if (v == null || !choices.contains(v)) throw new ApiException(
      400,
      "VALIDATION_ERROR",
      "選択値が不正です。"
    );
    return v;
  }

  private Map<String, Object> goalRow(UUID id) {
    return one("SELECT * FROM goals WHERE id=? AND user_id=?", id, OWNER);
  }

  private void active(UUID id) {
    if (!goalRow(id).get("status").equals("ACTIVE")) throw new ApiException(
      409,
      "INVALID_STATE_TRANSITION",
      "この目標への投稿は終了しています。"
    );
  }

  private Map<String, Object> goalDto(Map<String, Object> r) {
    return m(
      "id",
      r.get("id"),
      "title",
      r.get("title"),
      "successCriteria",
      r.get("success_criteria"),
      "description",
      r.get("description"),
      "category",
      r.get("category"),
      "deadline",
      r.get("deadline"),
      "status",
      r.get("status"),
      "createdAt",
      r.get("created_at"),
      "achievedAt",
      r.get("achieved_at"),
      "abandonedAt",
      r.get("abandoned_at")
    );
  }

  public Map<String, Object> goal(UUID id) {
    return goalDto(goalRow(id));
  }

  private String hash(String content) {
    try {
      return HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(
          content.getBytes(StandardCharsets.UTF_8)
        )
      );
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Map<String, Object> previous(String key, String content) {
    if (
      key == null || key.isBlank() || key.length() > 128
    ) throw new ApiException(
      400,
      "VALIDATION_ERROR",
      "Idempotency-Keyが必要です。"
    );
    db.update(
      "DELETE FROM idempotency_keys WHERE user_id=? AND created_at < ?",
      OWNER,
      Timestamp.from(clock.instant().minus(Duration.ofHours(24)))
    );
    var rows = db.queryForList(
      "SELECT * FROM idempotency_keys WHERE user_id=? AND key=?",
      OWNER,
      key
    );
    if (rows.isEmpty()) return null;
    var r = rows.get(0);
    if (!hash(content).equals(r.get("request_hash"))) throw new ApiException(
      409,
      "IDEMPOTENCY_CONFLICT",
      "同じキーで異なる操作はできません。"
    );
    return r;
  }

  public record Result(int status, Map<String, Object> body) {}

  private Result replay(Map<String, Object> r) {
    UUID g = (UUID) r.get("goal_id"),
      p = (UUID) r.get("progress_id"),
      roast = (UUID) r.get("roast_id");
    var out = new LinkedHashMap<String, Object>();
    if (p == null) out.put("goal", goal(g));
    else {
      var rows = db.queryForList(
        "SELECT * FROM progress_logs WHERE id=? AND user_id=? AND deleted_at IS NULL",
        p,
        OWNER
      );
      out.put("progressLog", rows.isEmpty() ? null : progressDto(rows.get(0)));
    }
    if (roast != null) out.put("roast", roast(roast));
    if (r.get("video_decision") != null) out.put(
      "videoDecision",
      r.get("video_decision")
    );
    return new Result(((Number) r.get("status_code")).intValue(), out);
  }

  private Result remember(
    String key,
    String content,
    UUID g,
    UUID p,
    UUID r,
    String decision,
    int status
  ) {
    db.update(
      "INSERT INTO idempotency_keys VALUES (?,?,?,?,?,?,?,?,?)",
      OWNER,
      key,
      hash(content),
      g,
      p,
      r,
      decision,
      status,
      now()
    );
    return replay(
      one(
        "SELECT * FROM idempotency_keys WHERE user_id=? AND key=?",
        OWNER,
        key
      )
    );
  }

  public record GoalInput(
    String title,
    String successCriteria,
    String description,
    String category,
    LocalDate deadline,
    boolean requestVideo
  ) {}

  public Result createGoal(String key, GoalInput in) {
    lock();
    String signature = "goal:" + in;
    var prev = previous(key, signature);
    if (prev != null) return replay(prev);
    String title = text(in.title, 100, true),
      criteria = text(in.successCriteria, 1000, true),
      description = text(in.description, 1000, false);
    String category = allowed(
      in.category == null ? "OTHER" : in.category,
      Set.of("STUDY", "HEALTH", "WORK", "HABIT", "CREATIVE", "OTHER")
    );
    if (
      in.deadline != null &&
      in.deadline.isBefore(
        LocalDate.now(clock.withZone(ZoneId.of("Asia/Tokyo")))
      )
    ) throw new ApiException(
      400,
      "VALIDATION_ERROR",
      "期限には今日以降の日付を指定してください。"
    );
    risk(title + criteria + description);
    UUID id = UUID.randomUUID();
    db.update(
      "INSERT INTO goals(id,user_id,title,success_criteria,description,category,deadline,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
      id,
      OWNER,
      title,
      criteria,
      description,
      category,
      in.deadline,
      now(),
      now()
    );
    UUID r = makeRoast(id, null, "GOAL_DECLARED", "DECLARATION", false);
    String decision = videoDecision(r, in.requestVideo);
    return remember(
      key,
      signature,
      id,
      null,
      r,
      decision,
      decision.equals("ACCEPTED") ? 202 : 201
    );
  }

  public record ProgressInput(
    String body,
    String progressCategory,
    boolean requestVideo
  ) {}

  public Result progress(UUID goal, String key, ProgressInput in) {
    lock();
    String signature = "progress:" + goal + ":" + in;
    var prev = previous(key, signature);
    if (prev != null) return replay(prev);
    active(goal);
    String body = text(in.body, 1000, true),
      category = allowed(
        in.progressCategory,
        Set.of("DONE", "PARTIAL", "NOT_DONE", "REST", "UNWELL")
      );
    risk(body);
    UUID id = UUID.randomUUID();
    db.update(
      "INSERT INTO progress_logs(id,goal_id,user_id,body,progress_category,request_video,created_at) VALUES (?,?,?,?,?,?,?)",
      id,
      goal,
      OWNER,
      body,
      category,
      in.requestVideo,
      now()
    );
    UUID r = makeRoast(goal, id, "PROGRESS_REPORTED", category, false);
    String decision = videoDecision(r, in.requestVideo);
    return remember(
      key,
      signature,
      goal,
      id,
      r,
      decision,
      decision.equals("ACCEPTED") ? 202 : 201
    );
  }

  private String videoDecision(UUID roast, boolean wanted) {
    return !wanted
      ? "NOT_REQUESTED"
      : paused()
        ? "PAUSED"
        : pipeline.enqueue(roast);
  }

  private UUID makeRoast(
    UUID goal,
    UUID progress,
    String trigger,
    String category,
    boolean victory
  ) {
    var previous = db.queryForList(
      "SELECT template_id FROM roasts WHERE goal_id=? AND user_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
      goal,
      OWNER
    );
    String last = previous.isEmpty()
      ? null
      : (String) previous.get(0).get("template_id");
    var reply = templates.reply(
      category,
      (String) goalRow(goal).get("success_criteria"),
      intensity(),
      last,
      paused(),
      victory
    );
    String kind = victory ? "VICTORY" : paused() ? "NEUTRAL" : "TAUNT";
    UUID id = UUID.randomUUID();
    db.update(
      "INSERT INTO roasts(id,user_id,goal_id,progress_log_id,trigger,response_kind,persona_id,voice_id,intensity,body,template_id,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
      id,
      OWNER,
      goal,
      progress,
      trigger,
      kind,
      "rival",
      "pending",
      intensity(),
      reply.text(),
      reply.templateId(),
      now()
    );
    if (progress != null) db.update(
      "INSERT INTO roast_context_sources VALUES (?,?)",
      id,
      progress
    );
    return id;
  }

  public Result close(UUID goal, String key, boolean achieve) {
    lock();
    String sig = "close:" + goal + ":" + achieve;
    var prev = previous(key, sig);
    if (prev != null) return replay(prev);
    active(goal);
    db.update(
      "UPDATE goals SET status=?,updated_at=?,achieved_at=?,abandoned_at=? WHERE id=? AND user_id=?",
      achieve ? "ACHIEVED" : "ABANDONED",
      now(),
      achieve ? now() : null,
      achieve ? null : now(),
      goal,
      OWNER
    );
    suppressPending(goal, "GOAL_CLOSED");
    UUID r = achieve
      ? makeRoast(goal, null, "GOAL_ACHIEVED", "DONE", true)
      : null;
    return remember(key, sig, goal, null, r, null, 200);
  }

  public void deleteProgress(UUID goal, UUID id) {
    lock();
    goalRow(goal);
    one(
      "SELECT id FROM progress_logs WHERE id=? AND goal_id=? AND user_id=?",
      id,
      goal,
      OWNER
    );
    db.update(
      "UPDATE progress_logs SET deleted_at=COALESCE(deleted_at,?) WHERE id=?",
      now(),
      id
    );
    db.update(
      "UPDATE roasts SET visibility='SUPPRESSED',suppression_reason='SOURCE_DELETED' WHERE user_id=? AND id IN (SELECT roast_id FROM roast_context_sources WHERE progress_log_id=?)",
      OWNER,
      id
    );
  }

  private Map<String, Object> progressDto(Map<String, Object> r) {
    return m(
      "id",
      r.get("id"),
      "goalId",
      r.get("goal_id"),
      "body",
      r.get("body"),
      "progressCategory",
      r.get("progress_category"),
      "createdAt",
      r.get("created_at")
    );
  }

  public Map<String, Object> roast(UUID id) {
    var r = one("SELECT * FROM roasts WHERE id=? AND user_id=?", id, OWNER);
    boolean hidden =
      r.get("visibility").equals("SUPPRESSED") ||
      (paused() && r.get("response_kind").equals("TAUNT"));
    var jobs = db.queryForList("SELECT * FROM roast_jobs WHERE roast_id=?", id);
    var job = jobs.isEmpty() ? Map.<String, Object>of() : jobs.get(0);
    String status = jobs.isEmpty() ? "TEXT_ONLY" : (String) job.get("status");
    boolean polling = Set.of(
      "TEXT_DONE",
      "AUDIO_DONE",
      "VIDEO_SUBMITTING",
      "VIDEO_SUBMITTED"
    ).contains(status);
    return m(
      "id",
      id,
      "status",
      status,
      "visibility",
      hidden ? "SUPPRESSED" : "VISIBLE",
      "personaId",
      r.get("persona_id"),
      "intensity",
      r.get("intensity"),
      "text",
      hidden ? null : r.get("body"),
      "audioUrl",
      !hidden && job.get("audio_key") != null
        ? "/api/proxy/roasts/" + id + "/media/audio"
        : null,
      "videoUrl",
      !hidden && status.equals("VIDEO_DONE")
        ? "/api/proxy/roasts/" + id + "/media/video"
        : null,
      "pollAfterMs",
      !hidden && polling ? 2000 : null,
      "createdAt",
      r.get("created_at")
    );
  }

  private record Cursor(Timestamp at, UUID id) {}

  private Cursor cursor(String value) {
    if (value == null) return new Cursor(
      Timestamp.from(Instant.parse("9999-01-01T00:00:00Z")),
      new UUID(-1, -1)
    );
    try {
      String[] p = new String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8
      ).split("\\|", -1);
      return new Cursor(
        Timestamp.from(Instant.parse(p[0])),
        UUID.fromString(p[1])
      );
    } catch (Exception e) {
      throw new ApiException(400, "VALIDATION_ERROR", "ページ指定が不正です。");
    }
  }

  private String encode(Map<String, Object> row) {
    return Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(
        (
          ((Timestamp) row.get("created_at")).toInstant() +
          "|" +
          row.get("id")
        ).getBytes(StandardCharsets.UTF_8)
      );
  }

  private int limit(int n) {
    if (n < 1 || n > 100) throw new ApiException(
      400,
      "VALIDATION_ERROR",
      "取得件数は1〜100です。"
    );
    return n;
  }

  public Map<String, Object> goals(String status, int count, String token) {
    int n = limit(count);
    var c = cursor(token);
    if (status != null) allowed(
      status,
      Set.of("ACTIVE", "ACHIEVED", "ABANDONED")
    );
    var rows = db.queryForList(
      "SELECT * FROM goals WHERE user_id=? AND (?::text IS NULL OR status=?) AND (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT ?",
      OWNER,
      status,
      status,
      c.at,
      c.id,
      n + 1
    );
    boolean more = rows.size() > n;
    if (more) rows = new ArrayList<>(rows.subList(0, n));
    return m(
      "items",
      rows.stream().map(this::goalDto).toList(),
      "nextCursor",
      more ? encode(rows.get(n - 1)) : null
    );
  }

  public Map<String, Object> timeline(UUID goal, int count, String token) {
    goalRow(goal);
    int n = limit(count);
    var c = cursor(token);
    var rows = db.queryForList(
      """
      SELECT * FROM (
        SELECT id,created_at,'PROGRESS' AS type FROM progress_logs WHERE goal_id=? AND user_id=? AND deleted_at IS NULL
        UNION ALL SELECT id,created_at,'ROAST' AS type FROM roasts WHERE goal_id=? AND user_id=? AND visibility='VISIBLE' AND (NOT ? OR response_kind<>'TAUNT')
        UNION ALL SELECT id,created_at,'GOAL_CREATED' AS type FROM goals WHERE id=? AND user_id=?
      ) t WHERE (created_at,id)<(?,?) ORDER BY created_at DESC,id DESC LIMIT ?
      """,
      goal,
      OWNER,
      goal,
      OWNER,
      paused(),
      goal,
      OWNER,
      c.at,
      c.id,
      n + 1
    );
    boolean more = rows.size() > n;
    if (more) rows = new ArrayList<>(rows.subList(0, n));
    var items = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      String type = (String) row.get("type");
      UUID id = (UUID) row.get("id");
      Object data = switch (type) {
        case "ROAST" -> roast(id);
        case "PROGRESS" -> progressDto(
          one("SELECT * FROM progress_logs WHERE id=?", id)
        );
        default -> goal(goal);
      };
      items.add(m("type", type, "at", row.get("created_at"), "data", data));
    }
    return m(
      "items",
      items,
      "nextCursor",
      more ? encode(rows.get(n - 1)) : null
    );
  }

  public Map<String, Object> me() {
    var s = settings();
    return m(
      "roastIntensity",
      s.get("roast_intensity"),
      "paused",
      paused(),
      "settingsRevision",
      s.get("revision"),
      "safety",
      m(
        "manualPaused",
        s.get("manual_paused"),
        "autoPaused",
        s.get("auto_paused"),
        "resumeAllowedAt",
        s.get("resume_allowed_at")
      ),
      "videoAvailability",
      m("available", availability().equals("STUB"), "reason", availability()),
      "budget",
      budget.snapshot(),
      "persona",
      personas().get(0)
    );
  }

  public List<Map<String, Object>> personas() {
    return List.of(
      m(
        "id",
        "rival",
        "name",
        "ライバル",
        "description",
        "同じ目線から、宣言と行動の差を突く。",
        "voice",
        m("id", "pending", "name", "音声は準備中", "creditText", "")
      )
    );
  }

  public record SettingsInput(String roastIntensity, Boolean paused) {}

  public Map<String, Object> updateSettings(SettingsInput input) {
    lock();
    String level = allowed(
      input.roastIntensity,
      Set.of("MILD", "NORMAL", "SAVAGE")
    );
    if (input.paused == null) throw new ApiException(
      400,
      "VALIDATION_ERROR",
      "停止状態が必要です。"
    );
    var s = settings();
    if (
      input.paused ||
      strength(level) < strength((String) s.get("roast_intensity"))
    ) suppressPending(null, input.paused ? "STOPPED" : "INTENSITY_LOWERED");
    boolean auto = Boolean.TRUE.equals(s.get("auto_paused"));
    if (
      !input.paused &&
      auto &&
      clock
        .instant()
        .isBefore(((Timestamp) s.get("resume_allowed_at")).toInstant())
    ) throw new ApiException(
      409,
      "SAFE_RESUME_TOO_EARLY",
      "安全停止から24時間後に再開できます。"
    );
    db.update(
      "UPDATE user_settings SET roast_intensity=?,manual_paused=?,auto_paused=?,revision=revision+1 WHERE user_id=?",
      level,
      input.paused,
      input.paused && auto,
      OWNER
    );
    return me();
  }

  private int strength(String level) {
    return switch (level) {
      case "MILD" -> 0;
      case "SAVAGE" -> 2;
      default -> 1;
    };
  }

  private boolean slotBusy() {
    return (
      db
        .queryForMap("SELECT * FROM generation_slot WHERE singleton_id=1")
        .get("operation_id") != null
    );
  }

  private void suppressPending(UUID goal, String reason) {
    db.update(
      "UPDATE roasts SET visibility='SUPPRESSED',suppression_reason=? WHERE user_id=? AND (?::uuid IS NULL OR goal_id=?) AND id IN (SELECT roast_id FROM roast_jobs WHERE status IN ('TEXT_DONE','AUDIO_DONE','VIDEO_SUBMITTING','VIDEO_SUBMITTED'))",
      reason,
      OWNER,
      goal,
      goal
    );
  }

  public String mediaKey(UUID id, String kind) {
    var r = roast(id);
    if (
      !r.get("visibility").equals("VISIBLE") ||
      !Set.of("audio", "video").contains(kind) ||
      r.get(kind + "Url") == null
    ) throw new ApiException(
      404,
      "RESOURCE_NOT_FOUND",
      "メディアを取得できません。"
    );
    return (String) one("SELECT * FROM roast_jobs WHERE roast_id=?", id).get(
      kind + "_key"
    );
  }

  private String availability() {
    if (paused()) return "PAUSED";
    if (!pipeline.enabled()) return "UNAVAILABLE";
    var s = budget.snapshot();
    if (s.dailyLimitMicroUsd() == null) return "UNAVAILABLE";
    if (
      200000 > s.dailyLimitMicroUsd() - s.dailyCommittedMicroUsd()
    ) return "DAILY_BUDGET";
    if (
      200000 > s.monthlyLimitMicroUsd() - s.monthlyCommittedMicroUsd()
    ) return "MONTHLY_BUDGET";
    return slotBusy() ? "BUSY" : "STUB";
  }
}
