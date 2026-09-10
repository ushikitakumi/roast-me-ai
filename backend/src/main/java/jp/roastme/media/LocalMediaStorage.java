package jp.roastme.media;

import java.io.IOException;
import java.nio.file.*;
import org.springframework.stereotype.Component;

@Component
public class LocalMediaStorage {

  private final Path root = Path.of("build/media").toAbsolutePath().normalize();

  private Path resolve(String key) {
    Path p = root.resolve(key).normalize();
    if (!p.startsWith(root)) throw new IllegalArgumentException(
      "Invalid media key"
    );
    return p;
  }

  public void put(String key, byte[] data) {
    try {
      Path p = resolve(key);
      Files.createDirectories(p.getParent());
      Path tmp = Files.createTempFile(p.getParent(), "media-", ".tmp");
      Files.write(tmp, data);
      Files.move(
        tmp,
        p,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE
      );
    } catch (IOException e) {
      throw new IllegalStateException("MEDIA_WRITE_FAILED", e);
    }
  }

  public byte[] get(String key) {
    try {
      return Files.readAllBytes(resolve(key));
    } catch (IOException e) {
      throw new IllegalStateException("MEDIA_READ_FAILED", e);
    }
  }
}
