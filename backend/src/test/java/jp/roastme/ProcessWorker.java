package jp.roastme;

import java.nio.file.*;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import jp.roastme.budget.BudgetService;
import jp.roastme.domain.AvatarVideoGenerator;
import jp.roastme.media.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Test-only entry point: an independent application JVM, killed by the parent test. */
public class ProcessWorker {

  private static void checkpoint(String stage) {
    if (!stage.equals(System.getenv("TEST_CHECKPOINT"))) return;
    try {
      Files.writeString(Path.of(System.getenv("TEST_READY_FILE")), stage);
      new CountDownLatch(1).await();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static void main(String[] args) {
    try (
      var app = new SpringApplicationBuilder(RoastMeApplication.class).run(
        "--server.port=0",
        "--app.stub-video=false"
      )
    ) {
      var provider = app.getBean(StubMediaProvider.class);
      var video = new AvatarVideoGenerator() {
        public String submit(UUID id, byte[] audio) {
          String external = provider.submit(id, audio);
          checkpoint("ACCEPTED_BEFORE_ACK");
          return external;
        }

        public VideoStatus status(String external) {
          app
            .getBean(JdbcTemplate.class)
            .update("INSERT INTO process_test_reads VALUES (?)", external);
          return provider.status(external);
        }

        public byte[] download(String external) {
          return provider.download(external);
        }
      };
      var worker = new MediaPipeline(
        app.getBean(JdbcTemplate.class),
        app.getBean(PlatformTransactionManager.class),
        Clock.systemUTC(),
        provider,
        video,
        app.getBean(LocalMediaStorage.class),
        app.getBean(BudgetService.class),
        true,
        600
      );
      UUID id = UUID.fromString(System.getenv("TEST_ROAST_ID"));
      for (int i = 0; i < 4; i++) {
        worker.runOnce(id);
        String state = app
          .getBean(JdbcTemplate.class)
          .queryForObject(
            "SELECT status FROM roast_jobs WHERE roast_id=?",
            String.class,
            id
          );
        checkpoint(state);
      }
    }
  }
}
