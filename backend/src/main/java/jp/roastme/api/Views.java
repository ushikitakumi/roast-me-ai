package jp.roastme.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.*;
import java.util.*;

public final class Views {

  @Schema(
    requiredProperties = {
      "id",
      "title",
      "successCriteria",
      "description",
      "status",
      "category",
      "deadline",
      "createdAt",
      "achievedAt",
      "abandonedAt",
    }
  )
  public record Goal(
    UUID id,
    String title,
    String successCriteria,
    String description,
    String status,
    String category,
    @Schema(nullable = true) LocalDate deadline,
    Instant createdAt,
    @Schema(nullable = true) Instant achievedAt,
    @Schema(nullable = true) Instant abandonedAt
  ) {}

  @Schema(
    requiredProperties = {
      "id",
      "status",
      "visibility",
      "personaId",
      "intensity",
      "text",
      "audioUrl",
      "videoUrl",
      "pollAfterMs",
      "createdAt",
    }
  )
  public record Roast(
    UUID id,
    String status,
    String visibility,
    String personaId,
    String intensity,
    @Schema(nullable = true) String text,
    @Schema(nullable = true) String audioUrl,
    @Schema(nullable = true) String videoUrl,
    @Schema(nullable = true) Integer pollAfterMs,
    Instant createdAt
  ) {}

  @Schema(
    requiredProperties = {
      "id",
      "goalId",
      "body",
      "progressCategory",
      "createdAt",
    }
  )
  public record Progress(
    UUID id,
    UUID goalId,
    String body,
    String progressCategory,
    Instant createdAt
  ) {}

  @Schema(requiredProperties = { "items", "nextCursor" })
  public record GoalList(
    List<Goal> items,
    @Schema(nullable = true) String nextCursor
  ) {}

  @Schema(
    requiredProperties = { "manualPaused", "autoPaused", "resumeAllowedAt" }
  )
  public record Safety(
    boolean manualPaused,
    boolean autoPaused,
    @Schema(nullable = true) Instant resumeAllowedAt
  ) {}

  @Schema(requiredProperties = { "available", "reason" })
  public record Availability(boolean available, String reason) {}

  @Schema(requiredProperties = { "id", "name", "creditText" })
  public record Voice(String id, String name, String creditText) {}

  @Schema(requiredProperties = { "id", "name", "description", "voice" })
  public record Persona(
    String id,
    String name,
    String description,
    Voice voice
  ) {}

  @Schema(
    requiredProperties = {
      "timezone",
      "monthlyLimitMicroUsd",
      "dailyLimitMicroUsd",
      "dailyCommittedMicroUsd",
      "monthlyCommittedMicroUsd",
    }
  )
  public record Budget(
    String timezone,
    long monthlyLimitMicroUsd,
    @Schema(nullable = true) Long dailyLimitMicroUsd,
    long dailyCommittedMicroUsd,
    long monthlyCommittedMicroUsd
  ) {}

  @Schema(
    requiredProperties = {
      "roastIntensity",
      "paused",
      "settingsRevision",
      "safety",
      "videoAvailability",
      "budget",
      "persona",
    }
  )
  public record Me(
    String roastIntensity,
    boolean paused,
    long settingsRevision,
    Safety safety,
    Availability videoAvailability,
    Budget budget,
    Persona persona
  ) {}

  public record GoalResult(Goal goal, Roast roast, String videoDecision) {}

  public record ProgressResult(
    @Schema(nullable = true) Progress progressLog,
    Roast roast,
    String videoDecision
  ) {}

  @Schema(requiredProperties = { "type", "at", "data" })
  public record TimelineItem(
    String type,
    Instant at,
    @Schema(oneOf = { Goal.class, Roast.class, Progress.class }) Object data
  ) {}

  @Schema(requiredProperties = { "items", "nextCursor" })
  public record Timeline(
    List<TimelineItem> items,
    @Schema(nullable = true) String nextCursor
  ) {}
}
