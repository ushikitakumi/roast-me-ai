package jp.roastme;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.*;
import jp.roastme.api.ApiException;
import jp.roastme.application.CoreService;
import jp.roastme.application.CoreService.*;
import jp.roastme.domain.TemplateRoaster;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@SpringBootTest(
  properties = {
    "app.daily-limit-micro-usd=1000000",
    "app.backend-user=test",
    "app.backend-password=test-password-with-16-chars",
  }
)
class CoreIntegrationTest {

  @Container
  static PostgreSQLContainer postgres = new PostgreSQLContainer(
    "postgres:16-alpine"
  );

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  CoreService service;

  @Autowired
  org.springframework.test.web.servlet.MockMvc mvc;

  @Autowired
  JdbcTemplate db;

  @Autowired
  jp.roastme.budget.BudgetService budget;

  @Autowired
  org.springframework.transaction.PlatformTransactionManager transactions;

  @Autowired
  jp.roastme.media.StubMediaProvider provider;

  @Autowired
  jp.roastme.media.LocalMediaStorage storage;

  private jp.roastme.media.MediaPipeline pipeline(
    jp.roastme.domain.AvatarVideoGenerator video
  ) {
    return new jp.roastme.media.MediaPipeline(
      db,
      transactions,
      Clock.systemUTC(),
      provider,
      video,
      storage,
      budget,
      true,
      600
    );
  }

  private UUID pendingRoast() {
    return (UUID) part(
      service.progress(
        goal(),
        UUID.randomUUID().toString(),
        new ProgressInput("10分進んだ", "PARTIAL", false)
      ),
      "roast"
    ).get("id");
  }

  @BeforeEach
  void clear() {
    db.execute(
      "TRUNCATE stub_video_submissions,roast_jobs,cost_entries,budget_reservations CASCADE"
    );
    db.update("UPDATE generation_slot SET operation_id=NULL");
    db.execute(
      "TRUNCATE idempotency_keys,roast_context_sources,roasts,progress_logs,goals CASCADE"
    );
    db.update(
      "UPDATE user_settings SET auto_paused=false,manual_paused=false,resume_allowed_at=null,roast_intensity='NORMAL'"
    );
  }

  private GoalInput input() {
    return new GoalInput(
      "参考書",
      "1冊を解く",
      "毎日30分",
      "STUDY",
      null,
      false
    );
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> part(Result result, String name) {
    return (Map<String, Object>) result.body().get(name);
  }

  private UUID goal() {
    return (UUID) part(
      service.createGoal(UUID.randomUUID().toString(), input()),
      "goal"
    ).get("id");
  }

  @Test
  void freeLoopIsPersistentIdempotentAndAlwaysRewards() {
    var first = service.createGoal("create", input());
    assertThat(service.createGoal("create", input()).body()).isEqualTo(
      first.body()
    );
    UUID id = (UUID) part(first, "goal").get("id");
    var report = service.progress(
      id,
      "report",
      new ProgressInput("10分できた", "PARTIAL", false)
    );
    assertThat(report.status()).isEqualTo(201);
    assertThat(part(report, "roast").get("status")).isEqualTo("TEXT_ONLY");
    service.updateSettings(new SettingsInput("NORMAL", true));
    var win = service.close(id, "win", true);
    assertThat(part(win, "roast").get("text"))
      .asString()
      .contains("今回はお前の勝ち");
    assertThat(part(win, "roast").get("visibility")).isEqualTo("VISIBLE");
    assertThat(service.close(id, "win", true).body()).isEqualTo(win.body());
    assertThatThrownBy(() ->
      service.progress(id, "other", new ProgressInput("進んだ", "DONE", false))
    ).isInstanceOf(ApiException.class);
  }

  @Test
  void deletionSuppressesReferencesAndIdempotentReplayWithoutDeletingLaterReport() {
    UUID g = goal();
    var first = service.progress(
      g,
      "first",
      new ProgressInput("誤入力", "NOT_DONE", false)
    );
    var later = service.progress(
      g,
      "later",
      new ProgressInput("実際は進んだ", "DONE", false)
    );
    UUID p = (UUID) part(first, "progressLog").get("id"),
      r = (UUID) part(later, "roast").get("id");
    db.update("INSERT INTO roast_context_sources VALUES (?,?)", r, p);
    service.deleteProgress(g, p);
    service.deleteProgress(g, p);
    assertThat(service.roast(r).get("text")).isNull();
    var replay = service.progress(
      g,
      "first",
      new ProgressInput("誤入力", "NOT_DONE", false)
    );
    assertThat(replay.body().get("progressLog")).isNull();
    assertThat(part(replay, "roast").get("text")).isNull();
    assertThat(
      db.queryForObject(
        "SELECT count(*) FROM progress_logs WHERE deleted_at IS NULL",
        Integer.class
      )
    ).isEqualTo(1);
  }

  @Test
  void normalRestDoesNotPauseButSeriousRiskRequiresDelayAndExplicitResume() {
    UUID g = goal();
    service.progress(
      g,
      "rest",
      new ProgressInput("体調が悪いので休んだ", "UNWELL", false)
    );
    assertThat(service.me().get("paused")).isEqualTo(false);
    service.progress(
      g,
      "risk",
      new ProgressInput("死にたい", "NOT_DONE", false)
    );
    assertThat(service.me().get("paused")).isEqualTo(true);
    assertThatThrownBy(() ->
      service.updateSettings(new SettingsInput("NORMAL", false))
    ).isInstanceOf(ApiException.class);
    db.update(
      "UPDATE user_settings SET resume_allowed_at=now()-interval '1 second'"
    );
    assertThat(service.me().get("paused")).isEqualTo(true);
    service.updateSettings(new SettingsInput("NORMAL", false));
    assertThat(service.me().get("paused")).isEqualTo(false);
  }

  @Test
  void rejectsInvalidInputsAndCrossOwnerResource() {
    assertThatThrownBy(() ->
      service.createGoal(
        "bad",
        new GoalInput(" ", "a", "", "OTHER", null, false)
      )
    ).isInstanceOf(ApiException.class);
    service.createGoal("same", input());
    assertThatThrownBy(() ->
      service.createGoal(
        "same",
        new GoalInput("changed", "a", "", "OTHER", null, false)
      )
    ).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.goal(UUID.randomUUID())).isInstanceOf(
      ApiException.class
    );
    assertThatThrownBy(() -> service.goals(null, 101, null)).isInstanceOf(
      ApiException.class
    );
  }

  @Test
  void paginationAndTemplateVariation() {
    UUID g = goal();
    var a = service.progress(
      g,
      "a",
      new ProgressInput("一歩", "PARTIAL", true)
    );
    var b = service.progress(
      g,
      "b",
      new ProgressInput("二歩", "PARTIAL", false)
    );
    assertThat(a.body().get("videoDecision")).isEqualTo("UNAVAILABLE");
    assertThat(part(a, "roast").get("text")).isNotEqualTo(
      part(b, "roast").get("text")
    );
    var page = service.timeline(g, 2, null);
    assertThat(page.get("nextCursor")).isNotNull();
    assertThat(
      service.timeline(g, 2, (String) page.get("nextCursor")).get("items")
    )
      .asList()
      .hasSize(2);
  }

  @Test
  void reservesBeforeSpendingAndKeepsUnknownAcrossDates() {
    var first = budget.reserve(UUID.randomUUID(), 700000);
    assertThat(first.accepted()).isTrue();
    assertThat(budget.reserve(UUID.randomUUID(), 100000).decision()).isEqualTo(
      "BUSY"
    );
    budget.unknown(first.id());
    db.update(
      "UPDATE budget_reservations SET budget_day=budget_day-1,budget_month=budget_month-interval '1 month'"
    );
    assertThat(budget.snapshot().dailyCommittedMicroUsd()).isEqualTo(700000);
    assertThat(budget.reserve(UUID.randomUUID(), 400000).decision()).isEqualTo(
      "DAILY_BUDGET"
    );
    assertThat(budget.reserve(UUID.randomUUID(), 200000).accepted()).isTrue();
  }

  @Test
  void settlesEachAttemptOnceAndReleasesOnlyConfirmedRemainder() {
    var r = budget.reserve(UUID.randomUUID(), 600000);
    UUID attempt = UUID.randomUUID();
    budget.settleAttempt(r.id(), attempt, "VIDEO", "stub", 500000, 400000);
    budget.settleAttempt(r.id(), attempt, "VIDEO", "stub", 500000, 400000);
    assertThat(budget.snapshot().dailyCommittedMicroUsd()).isEqualTo(500000);
    budget.finishConfirmed(r.id());
    assertThat(budget.snapshot().dailyCommittedMicroUsd()).isEqualTo(400000);
  }

  @Test
  void durableStagesResumeWithoutSecondSubmission() {
    UUID id = pendingRoast();
    var pipeline = pipeline(provider);
    assertThat(pipeline.enqueue(id)).isEqualTo("ACCEPTED");
    pipeline.runOnce(id);
    assertThat(service.roast(id).get("status")).isEqualTo("AUDIO_DONE");
    pipeline.runOnce(id);
    assertThat(service.roast(id).get("status")).isEqualTo("VIDEO_SUBMITTED");
    // A new worker instance represents restart; all progress is in PostgreSQL.
    pipeline(provider).runOnce(id);
    pipeline(provider).runOnce(id);
    assertThat(service.roast(id).get("status")).isEqualTo("VIDEO_DONE");
    assertThat(
      db.queryForObject(
        "SELECT count(*) FROM stub_video_submissions",
        Integer.class
      )
    ).isEqualTo(1);
    assertThat(storage.get(service.mediaKey(id, "video"))).isNotEmpty();
    assertThat(budget.snapshot().dailyCommittedMicroUsd()).isZero();
  }

  @Test
  void unknownSubmissionDoesNotRetryOrReleaseBudget() {
    UUID id = pendingRoast();
    var uncertain = new jp.roastme.domain.AvatarVideoGenerator() {
      public String submit(UUID id, byte[] bytes) {
        provider.submit(id, bytes);
        throw new IllegalStateException("connection lost after acceptance");
      }

      public VideoStatus status(String id) {
        return VideoStatus.COMPLETE;
      }

      public byte[] download(String id) {
        return new byte[0];
      }
    };
    var p = pipeline(uncertain);
    p.enqueue(id);
    p.runOnce(id);
    p.runOnce(id);
    p.runOnce(id);
    assertThat(service.roast(id).get("status")).isEqualTo("SUBMISSION_UNKNOWN");
    assertThat(
      db.queryForObject(
        "SELECT count(*) FROM stub_video_submissions",
        Integer.class
      )
    ).isEqualTo(1);
    assertThat(budget.snapshot().dailyCommittedMicroUsd()).isEqualTo(200000);
    assertThat(budget.reserve(UUID.randomUUID(), 100000).accepted()).isTrue();
  }

  @Test
  void abandonedLeaseAndResultDeadlineDoNotResubmit() {
    UUID id = pendingRoast();
    var p = pipeline(provider);
    p.enqueue(id);
    p.runOnce(id);
    db.update(
      "UPDATE roast_jobs SET status='VIDEO_SUBMITTING',lease_expires_at=now()-interval '1 second' WHERE roast_id=?",
      id
    );
    p.runOnce(id);
    assertThat(service.roast(id).get("status")).isEqualTo("SUBMISSION_UNKNOWN");
    assertThat(
      db.queryForObject(
        "SELECT count(*) FROM stub_video_submissions",
        Integer.class
      )
    ).isZero();
    UUID other = pendingRoast();
    p.enqueue(other);
    p.runOnce(other);
    p.runOnce(other);
    db.update(
      "UPDATE roast_jobs SET result_deadline_at=now()-interval '1 second' WHERE roast_id=?",
      other
    );
    p.runOnce(other);
    assertThat(service.roast(other).get("status")).isEqualTo("RESULT_UNKNOWN");
    assertThat(service.roast(other).get("videoUrl")).isNull();
  }

  @Test
  void stopPreventsPendingMediaAndNeverResurrectsIt() {
    UUID id = pendingRoast();
    var p = pipeline(provider);
    p.enqueue(id);
    service.updateSettings(new SettingsInput("NORMAL", true));
    p.runOnce(id);
    service.updateSettings(new SettingsInput("NORMAL", false));
    assertThat(service.roast(id).get("visibility")).isEqualTo("SUPPRESSED");
    assertThat(service.roast(id).get("status")).isEqualTo("CANCELLED");
    assertThatThrownBy(() -> service.mediaKey(id, "audio")).isInstanceOf(
      ApiException.class
    );
    assertThat(
      db.queryForObject(
        "SELECT count(*) FROM stub_video_submissions",
        Integer.class
      )
    ).isZero();
  }

  enum RaceStage {
    AUDIO,
    SUBMIT,
    DOWNLOAD,
  }

  enum Invalidation {
    STOP,
    LOWER_INTENSITY,
    ACHIEVE,
    ABANDON,
    DELETE,
    DELETE_SOURCE,
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> generationRaces() {
    return Arrays.stream(RaceStage.values()).flatMap(stage ->
      Arrays.stream(Invalidation.values()).map(action ->
        org.junit.jupiter.params.provider.Arguments.of(stage, action)
      )
    );
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{0} / {1}")
  @org.junit.jupiter.params.provider.MethodSource("generationRaces")
  void invalidationDuringExternalWorkNeverRepublishesMedia(
    RaceStage stage,
    Invalidation action
  ) throws Exception {
    UUID g = goal();
    var source = service.progress(
      g,
      "source",
      new ProgressInput("先の報告", "PARTIAL", false)
    );
    UUID sourceId = (UUID) part(source, "progressLog").get("id");
    var input = new ProgressInput("続きの報告", "PARTIAL", false);
    var report = service.progress(g, "race", input);
    UUID reportId = (UUID) part(report, "progressLog").get("id");
    UUID id = (UUID) part(report, "roast").get("id");
    db.update("INSERT INTO roast_context_sources VALUES (?,?)", id, sourceId);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    Runnable barrier = () -> {
      entered.countDown();
      try {
        if (
          !release.await(15, java.util.concurrent.TimeUnit.SECONDS)
        ) throw new IllegalStateException("Test did not release provider");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    };
    var video = new jp.roastme.domain.AvatarVideoGenerator() {
      public String submit(UUID roast, byte[] audio) {
        String external = provider.submit(roast, audio);
        // Acceptance happened, but its acknowledgement is still in flight.
        if (stage == RaceStage.SUBMIT) barrier.run();
        return external;
      }

      public VideoStatus status(String external) {
        return provider.status(external);
      }

      public byte[] download(String external) {
        byte[] bytes = provider.download(external);
        // Completed bytes have arrived; publication has not committed yet.
        if (stage == RaceStage.DOWNLOAD) barrier.run();
        return bytes;
      }
    };
    var worker = new jp.roastme.media.MediaPipeline(
      db,
      transactions,
      Clock.systemUTC(),
      text -> {
        byte[] bytes = provider.synthesize(text);
        if (stage == RaceStage.AUDIO) barrier.run();
        return bytes;
      },
      video,
      storage,
      budget,
      true,
      600
    );
    assertThat(worker.enqueue(id)).isEqualTo("ACCEPTED");
    if (stage != RaceStage.AUDIO) worker.runOnce(id);
    if (stage == RaceStage.DOWNLOAD) worker.runOnce(id);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var running = executor.submit(() -> worker.runOnce(id));
      assertThat(
        entered.await(10, java.util.concurrent.TimeUnit.SECONDS)
      ).isTrue();
      // A separate transaction must commit while the provider is blocked.
      executor
        .submit(() -> {
          switch (action) {
            case STOP -> service.updateSettings(
              new SettingsInput("NORMAL", true)
            );
            case LOWER_INTENSITY -> service.updateSettings(
              new SettingsInput("MILD", false)
            );
            case ACHIEVE -> service.close(g, "finish", true);
            case ABANDON -> service.close(g, "finish", false);
            case DELETE -> service.deleteProgress(g, reportId);
            case DELETE_SOURCE -> service.deleteProgress(g, sourceId);
          }
        })
        .get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertHiddenEverywhere(g, id, input);
      // Resume/restore before the old worker returns: invalidation must be permanent.
      if (
        action == Invalidation.STOP || action == Invalidation.LOWER_INTENSITY
      ) service.updateSettings(new SettingsInput("NORMAL", false));
      assertHiddenEverywhere(g, id, input);
      release.countDown();
      running.get(10, java.util.concurrent.TimeUnit.SECONDS);
      worker.runOnce(id);
      worker.runOnce(id);
      assertHiddenEverywhere(g, id, input);
      assertThat(
        db.queryForObject(
          "SELECT status FROM roast_jobs WHERE roast_id=?",
          String.class,
          id
        )
      ).isEqualTo(stage == RaceStage.AUDIO ? "CANCELLED" : "VIDEO_DONE");
      assertThat(
        db.queryForObject(
          "SELECT count(*) FROM stub_video_submissions",
          Integer.class
        )
      ).isEqualTo(stage == RaceStage.AUDIO ? 0 : 1);
      assertThat(budget.snapshot().dailyCommittedMicroUsd()).isZero();
      assertThat(
        db.queryForObject(
          "SELECT operation_id FROM generation_slot",
          UUID.class
        )
      ).isNull();
      if (action == Invalidation.DELETE_SOURCE) {
        assertThat(
          db.queryForObject(
            "SELECT deleted_at IS NULL FROM progress_logs WHERE id=?",
            Boolean.class,
            reportId
          )
        ).isTrue();
        assertThat(
          service
            .roast((UUID) part(source, "roast").get("id"))
            .get("visibility")
        ).isEqualTo("SUPPRESSED");
      }
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertThat(
        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
      ).isTrue();
    }
  }

  @SuppressWarnings("unchecked")
  private void assertHiddenEverywhere(
    UUID goal,
    UUID roast,
    ProgressInput input
  ) {
    var view = service.roast(roast);
    assertThat(view.get("visibility")).isEqualTo("SUPPRESSED");
    for (String field : List.of("text", "audioUrl", "videoUrl", "pollAfterMs"))
      assertThat(view.get(field)).as(field).isNull();
    assertThat(part(service.progress(goal, "race", input), "roast")).isEqualTo(
      view
    );
    var items = (List<Map<String, Object>>) service
      .timeline(goal, 100, null)
      .get("items");
    assertThat(items).noneMatch(
      item ->
        item.get("type").equals("ROAST") &&
        ((Map<String, Object>) item.get("data")).get("id").equals(roast)
    );
    for (String kind : List.of("audio", "video"))
      assertThatThrownBy(() -> service.mediaKey(roast, kind)).isInstanceOf(
        ApiException.class
      );
  }

  @Test
  void apiRequiresAuthenticationAndExposesGeneratedContract() throws Exception {
    mvc
      .perform(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
          "/api/v1/me"
        )
      )
      .andExpect(
        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized()
      );
    mvc
      .perform(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
          "/api/v1/me"
        ).with(
          org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic(
            "test",
            "test-password-with-16-chars"
          )
        )
      )
      .andExpect(
        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()
      )
      .andExpect(
        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
          "$.videoAvailability.available"
        ).value(false)
      );
    var response = mvc
      .perform(
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
          "/v3/api-docs"
        ).with(
          org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic(
            "test",
            "test-password-with-16-chars"
          )
        )
      )
      .andExpect(
        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()
      )
      .andReturn()
      .getResponse();
    var mapper = new tools.jackson.databind.json.JsonMapper();
    var actual = (tools.jackson.databind.node.ObjectNode) mapper.readTree(
      response.getContentAsString()
    );
    actual.remove("servers");
    var expected = (tools.jackson.databind.node.ObjectNode) mapper.readTree(
      java.nio.file.Files.readString(java.nio.file.Path.of("openapi.json"))
    );
    expected.remove("servers");
    assertThat(actual)
      .as("Regenerate backend/openapi.json when the API changes")
      .isEqualTo(expected);
  }

  @Test
  void monthlyBudgetCannotBeBypassedByDailyRollover() {
    var r = budget.reserve(UUID.randomUUID(), 100000);
    budget.settleAttempt(
      r.id(),
      UUID.randomUUID(),
      "VIDEO",
      "test",
      100000,
      20000000
    );
    budget.finishConfirmed(r.id());
    db.update(
      "UPDATE budget_reservations SET budget_day=budget_day-1 WHERE id=?",
      r.id()
    );
    assertThat(budget.reserve(UUID.randomUUID(), 1).decision()).isEqualTo(
      "MONTHLY_BUDGET"
    );
  }

  @Test
  void concurrentReservationsOnlyAllocateOneSlot() throws Exception {
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var results = executor.invokeAll(
        List.of(
          () -> budget.reserve(UUID.randomUUID(), 300000),
          () -> budget.reserve(UUID.randomUUID(), 300000)
        )
      );
      long accepted = 0;
      for (var result : results)
        if (
          (
            (jp.roastme.budget.BudgetService.Reservation) result.get()
          ).accepted()
        ) accepted++;
      assertThat(accepted).isEqualTo(1);
      assertThat(budget.snapshot().dailyCommittedMicroUsd()).isEqualTo(300000);
    } finally {
      executor.shutdownNow();
    }
  }
}
