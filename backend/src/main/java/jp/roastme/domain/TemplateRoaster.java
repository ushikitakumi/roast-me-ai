package jp.roastme.domain;

public final class TemplateRoaster {

  public record Reply(String text, String templateId) {}

  public Reply reply(
    String category,
    String criteria,
    String intensity,
    String previous,
    boolean paused,
    boolean victory
  ) {
    if (victory) return new Reply(
      "『" +
        criteria +
        "』を達成したんだな。今回はお前の勝ちだ、やり切ったのは認める。",
      "victory"
    );
    if (paused) return new Reply("記録しました。", "paused");
    String prefix = switch (category) {
      case "DONE" -> "今日はやり切ったんだな。";
      case "PARTIAL" -> "『" + criteria + "』に向けて、一歩は進んだな。";
      case "NOT_DONE" -> "今日はできなかったんだな。";
      case "REST" -> "今日は休むんだな。";
      case "UNWELL" -> "休む判断に文句はないよ。";
      default -> "『" + criteria + "』、宣言は聞いたよ。";
    };
    String[] endings = switch (intensity) {
      case "MILD" -> new String[] {
        "次に動くとき、ちょっと期待してるよ。",
        "次も実力を見せてね。",
      };
      case "SAVAGE" -> new String[] {
        "次に動くと決めたら、口だけじゃないところ見せろよ。",
        "次も行動で返せるか、見せてもらうよ。",
      };
      default -> new String[] {
        "次に動くと決めたとき、実力見せてよ。",
        "次も続いたら認めてやるよ。",
      };
    };
    String base = category + ":" + intensity + ":";
    int variant = (base + "0").equals(previous) ? 1 : 0;
    return new Reply(prefix + endings[variant], base + variant);
  }

  // Local conservative baseline; not a claim of complete risk detection.
  public boolean seriousRisk(String body) {
    return (
      body != null &&
      body.matches(
        "(?s).*(死にたい|自殺したい|自分を殺したい|自傷したい|命を絶ちたい).*"
      )
    );
  }
}
