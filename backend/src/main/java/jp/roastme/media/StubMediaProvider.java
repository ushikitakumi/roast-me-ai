package jp.roastme.media;

import java.nio.*;
import java.nio.file.*;
import java.util.UUID;
import jp.roastme.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Development fixture only: silent WAV and a clearly labelled synthetic test video. */
@Component
public class StubMediaProvider
  implements AvatarVideoGenerator, SpeechSynthesizer
{

  private final JdbcTemplate db;

  public StubMediaProvider(JdbcTemplate db) {
    this.db = db;
  }

  public byte[] synthesize(String text) {
    int samples = 24000;
    ByteBuffer b = ByteBuffer.allocate(44 + samples * 2).order(
      ByteOrder.LITTLE_ENDIAN
    );
    b.put("RIFF".getBytes())
      .putInt(36 + samples * 2)
      .put("WAVEfmt ".getBytes())
      .putInt(16)
      .putShort((short) 1)
      .putShort((short) 1)
      .putInt(24000)
      .putInt(48000)
      .putShort((short) 2)
      .putShort((short) 16)
      .put("data".getBytes())
      .putInt(samples * 2);
    return b.array();
  }

  public String submit(UUID roastId, byte[] audio) {
    String id = UUID.randomUUID().toString();
    db.update(
      "INSERT INTO stub_video_submissions VALUES (?,?,now())",
      id,
      roastId
    );
    return id;
  }

  public VideoStatus status(String externalId) {
    return db.queryForObject(
      "SELECT count(*) FROM stub_video_submissions WHERE id=?",
      Integer.class,
      externalId
    ) > 0
      ? VideoStatus.COMPLETE
      : VideoStatus.FAILED;
  }

  public byte[] download(String externalId) {
    try {
      return new org.springframework.core.io.ClassPathResource(
        "fixtures/stub.mp4"
      ).getContentAsByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("STUB_VIDEO_FIXTURE_MISSING");
    }
  }
}
