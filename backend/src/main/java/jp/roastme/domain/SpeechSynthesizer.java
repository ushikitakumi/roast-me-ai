package jp.roastme.domain;

public interface SpeechSynthesizer {
  byte[] synthesize(String text);
}
