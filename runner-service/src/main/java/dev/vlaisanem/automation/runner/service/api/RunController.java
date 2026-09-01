package dev.vlaisanem.automation.runner.service.api;

import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.orchestration.RunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.nio.file.Path;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP contract only - request/response mapping and status codes. All orchestration lives in {@link
 * RunService}; the allowlist lives in {@code RunRequestValidator}.
 */
@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

  private final RunService runService;

  public RunController(RunService runService) {
    this.runService = runService;
  }

  @Operation(operationId = "createRun")
  @ApiResponses({
    @ApiResponse(
        responseCode = "202",
        description = "Run accepted and queued.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = RunResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Malformed request, or an environment/suite combination not allowlisted.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "503",
        description =
            "The run queue is full, the runner is degraded, or the canonical event"
                + " journal is unavailable.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "500",
        description = "Unexpected server error.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class)))
  })
  @PostMapping
  public ResponseEntity<RunResponse> create(@Valid @RequestBody CreateRunRequest request) {
    Run run = runService.submit(request.environment(), request.suite());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(RunResponse.from(run));
  }

  @Operation(operationId = "getRun")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = RunResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "No run exists for the given runId.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "500",
        description = "Unexpected server error.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class)))
  })
  @GetMapping("/{runId}")
  public RunResponse get(@PathVariable String runId) {
    return RunResponse.from(runService.find(runId));
  }

  @Operation(operationId = "listRuns")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                array = @ArraySchema(schema = @Schema(implementation = RunResponse.class)))),
    @ApiResponse(
        responseCode = "500",
        description = "Unexpected server error.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class)))
  })
  @GetMapping
  public List<RunResponse> list() {
    return runService.findAll().stream().map(RunResponse::from).toList();
  }

  @Operation(operationId = "downloadRunLog")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        content =
            @Content(
                mediaType = MediaType.TEXT_PLAIN_VALUE,
                schema = @Schema(type = "string", format = "binary"))),
    @ApiResponse(
        responseCode = "404",
        description = "No run exists for the given runId, or its process log does not exist yet.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "500",
        description = "Unexpected server error.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class)))
  })
  @GetMapping(value = "/{runId}/log", produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<Resource> processLog(@PathVariable String runId) {
    Path logFile = runService.processLog(runId);
    Resource resource = new FileSystemResource(logFile);
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_PLAIN)
        .header(
            HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + runId + "-process.log\"")
        .body(resource);
  }

  @Operation(operationId = "cancelRun")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Cancellation requested (or the run was already terminal, a no-op).",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = RunResponse.class))),
    @ApiResponse(
        responseCode = "404",
        description = "No run exists for the given runId.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "503",
        description = "The runner is degraded or the canonical event journal is unavailable.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "500",
        description = "Unexpected server error.",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class)))
  })
  @PostMapping("/{runId}/cancel")
  public RunResponse cancel(@PathVariable String runId) {
    return RunResponse.from(runService.cancel(runId));
  }
}
