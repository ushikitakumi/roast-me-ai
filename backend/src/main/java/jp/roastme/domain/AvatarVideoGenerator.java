package jp.roastme.domain;

import java.util.UUID;

public interface AvatarVideoGenerator {
  String submit(UUID roastId, byte[] audio);
  VideoStatus status(String externalId);
  byte[] download(String externalId);

  enum VideoStatus {
    PROCESSING,
    COMPLETE,
    FAILED,
  }
}
