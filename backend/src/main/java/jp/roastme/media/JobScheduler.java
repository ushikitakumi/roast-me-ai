package jp.roastme.media;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.stub-video", havingValue = "true")
public class JobScheduler {

  private final MediaPipeline pipeline;

  public JobScheduler(MediaPipeline pipeline) {
    this.pipeline = pipeline;
  }

  @Scheduled(fixedDelay = 2000)
  public void recover() {
    for (var id : pipeline.runnable()) pipeline.runOnce(id);
  }
}
