package dev.vlaisanem.automation.runner.service.api;

import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Wire representation of a {@link Run}. Kept separate from the domain record (rather than
 * serializing {@link Run} directly) so an internal refactor of {@code Run} does not silently change
 * the REST contract. Null fields are omitted (see {@code spring.jackson.default-property-
 * inclusion} in application.yml), so {@code startedAt}/{@code finishedAt}/{@code exitCode}/{@code
 * detail} are absent - not present-with-null - for a non-terminal (or not-yet-started) run.
 *
 * <p>Every component here is annotated with an explicit {@code requiredMode}, deliberately, rather
 * than relying on springdoc's default inference: verified live against a real {@code /v3/api-docs}
 * response that, absent any Bean Validation annotation on a plain (non-validated) response record,
 * springdoc infers <em>no</em> {@code required} array at all - not even for {@code runId}, which is
 * always present. Leaving that to inference would have produced a generated TypeScript client where
 * every field, including ones that can never actually be absent, is optional. {@link
 * #processLogUrl()} is present from the moment the run is accepted, although the endpoint returns
 * {@code 404} until the process creates its log.
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
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String processLogUrl) {

  public static RunResponse from(Run run) {
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
        "/api/v1/runs/" + run.runId() + "/log");
  }
}
