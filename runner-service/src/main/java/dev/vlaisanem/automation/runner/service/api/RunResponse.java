package dev.vlaisanem.automation.runner.service.api;

import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Wire representation of a {@link Run}, kept separate so an internal refactor of {@code Run}
 * doesn't silently change the REST contract. Null fields are omitted, so {@code startedAt}/{@code
 * finishedAt}/{@code exitCode}/{@code detail} are absent - not present-with-null - for a
 * non-terminal run.
 *
 * <p>Every component carries an explicit {@code requiredMode}: springdoc infers no {@code required}
 * array at all for a plain (non-validated) response record, which would otherwise make every field
 * optional in the generated TypeScript client. {@link #processLogUrl()} is present as soon as the
 * run is accepted, though the endpoint 404s until the process creates its log.
 */
public record RunResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String runId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Environment environment,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Suite suite,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RunStatus status,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant requestedAt,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) Instant startedAt,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) Instant finishedAt,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) Integer exitCode,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String detail,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String processLogUrl,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<SelectedTestResponse> selectedTests,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean artifactsPurged) {

  /**
   * {@code artifactsPurged} defaults to {@code false}, correct for every caller except {@code
   * RunController#get}: a run built immediately after create/cancel can't already have had its
   * artifacts purged.
   */
  public static RunResponse from(Run run) {
    return from(run, false);
  }

  public static RunResponse from(Run run, boolean artifactsPurged) {
    return new RunResponse(
        run.runId(),
        run.environment(),
        run.suite(),
        run.status(),
        run.requestedAt(),
        run.startedAt(),
        run.finishedAt(),
        run.exitCode(),
        run.detail(),
        "/api/v1/runs/" + run.runId() + "/log",
        run.selectedTests().stream().map(SelectedTestResponse::from).toList(),
        artifactsPurged);
  }
}
