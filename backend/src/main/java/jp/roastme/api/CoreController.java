package jp.roastme.api;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.*;
import jp.roastme.application.CoreService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CoreController {

  private final CoreService service;
  private final jp.roastme.media.LocalMediaStorage media;

  public CoreController(
    CoreService service,
    jp.roastme.media.LocalMediaStorage media
  ) {
    this.service = service;
    this.media = media;
  }

  private ResponseEntity<?> result(CoreService.Result r) {
    return ResponseEntity.status(r.status())
      .cacheControl(CacheControl.noStore())
      .body(r.body());
  }

  @ApiResponse(
    responseCode = "200",
    content = @Content(schema = @Schema(implementation = Views.Me.class))
  )
  @GetMapping("/me")
  Object me() {
    return service.me();
  }

  @GetMapping("/personas")
  Object personas() {
    return service.personas();
  }

  @ApiResponse(
    responseCode = "200",
    content = @Content(schema = @Schema(implementation = Views.Me.class))
  )
  @PutMapping("/me/settings")
  Object settings(@RequestBody CoreService.SettingsInput in) {
    return service.updateSettings(in);
  }

  @ApiResponse(
    responseCode = "200",
    content = @Content(schema = @Schema(implementation = Views.GoalList.class))
  )
  @GetMapping("/goals")
  Object goals(
    @RequestParam(required = false) String status,
    @RequestParam(defaultValue = "20") int limit,
    @RequestParam(required = false) String cursor
  ) {
    return service.goals(status, limit, cursor);
  }

  @ApiResponse(
    responseCode = "200",
    content = @Content(schema = @Schema(implementation = Views.Goal.class))
  )
  @GetMapping("/goals/{id}")
  Object goal(@PathVariable UUID id) {
    return service.goal(id);
  }

  @ApiResponse(
    responseCode = "201",
    content = @Content(
      schema = @Schema(implementation = Views.GoalResult.class)
    )
  )
  @ApiResponse(
    responseCode = "202",
    content = @Content(
      schema = @Schema(implementation = Views.GoalResult.class)
    )
  )
  @PostMapping("/goals")
  ResponseEntity<?> create(
    @RequestHeader(value = "Idempotency-Key", required = false) String key,
    @RequestBody CoreService.GoalInput in
  ) {
    return result(service.createGoal(key, in));
  }

  @ApiResponse(
    responseCode = "201",
    content = @Content(
      schema = @Schema(implementation = Views.ProgressResult.class)
    )
  )
  @ApiResponse(
    responseCode = "202",
    content = @Content(
      schema = @Schema(implementation = Views.ProgressResult.class)
    )
  )
  @PostMapping("/goals/{id}/progress")
  ResponseEntity<?> progress(
    @PathVariable UUID id,
    @RequestHeader(value = "Idempotency-Key", required = false) String key,
    @RequestBody CoreService.ProgressInput in
  ) {
    return result(service.progress(id, key, in));
  }

  @ApiResponse(
    responseCode = "200",
    content = @Content(
      schema = @Schema(implementation = Views.GoalResult.class)
    )
  )
  @PostMapping("/goals/{id}/achieve")
  ResponseEntity<?> achieve(
    @PathVariable UUID id,
    @RequestHeader(value = "Idempotency-Key", required = false) String key
  ) {
    return result(service.close(id, key, true));
  }

  @ApiResponse(
    responseCode = "200",
    content = @Content(
      schema = @Schema(implementation = Views.GoalResult.class)
    )
  )
  @PostMapping("/goals/{id}/abandon")
  ResponseEntity<?> abandon(
    @PathVariable UUID id,
    @RequestHeader(value = "Idempotency-Key", required = false) String key
  ) {
    return result(service.close(id, key, false));
  }

  @DeleteMapping("/goals/{id}/progress/{progress}")
  ResponseEntity<?> delete(@PathVariable UUID id, @PathVariable UUID progress) {
    service.deleteProgress(id, progress);
    return ResponseEntity.noContent().build();
  }

  @ApiResponse(
    responseCode = "200",
    content = @Content(schema = @Schema(implementation = Views.Timeline.class))
  )
  @GetMapping("/goals/{id}/timeline")
  Object timeline(
    @PathVariable UUID id,
    @RequestParam(defaultValue = "20") int limit,
    @RequestParam(required = false) String cursor
  ) {
    return service.timeline(id, limit, cursor);
  }

  @ApiResponse(
    responseCode = "200",
    content = @Content(schema = @Schema(implementation = Views.Roast.class))
  )
  @GetMapping("/roasts/{id}")
  Object roast(@PathVariable UUID id) {
    return service.roast(id);
  }

  @GetMapping("/roasts/{id}/media/{kind}")
  ResponseEntity<org.springframework.core.io.Resource> media(
    @PathVariable UUID id,
    @PathVariable String kind
  ) {
    byte[] bytes = media.get(service.mediaKey(id, kind));
    return ResponseEntity.ok()
      .cacheControl(CacheControl.noStore())
      .contentType(
        MediaType.parseMediaType(
          kind.equals("audio") ? "audio/wav" : "video/mp4"
        )
      )
      .body(new org.springframework.core.io.ByteArrayResource(bytes));
  }
}
