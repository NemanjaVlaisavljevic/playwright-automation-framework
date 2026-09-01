package dev.vlaisanem.automation.runner.service.api;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.orchestration.RunRequestValidator;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;

/**
 * Wire representation of what a client is actually allowed to submit. {@link
 * RunRequestValidator#allowedCombinations()} is the single source of truth this mirrors, so a
 * frontend never has to hand-copy the allowlist and risk it silently drifting from what the server
 * will actually accept - a new environment or suite only ever appears here once it is also wired
 * into the validator itself.
 *
 * <p>Both this record and {@link EnvironmentCapabilities} copy their list components in their
 * compact constructors, so the response stays deeply immutable and its ordering deterministic
 * regardless of how a future caller constructs one directly - {@link #current()} already builds
 * sorted, unmodifiable lists, but callers should not have to know that to get the same guarantee.
 *
 * <p>Every component here is annotated with an explicit {@code requiredMode}, deliberately, the
 * same way {@link RunResponse} is - verified live against a real {@code /v3/api-docs} response
 * that, absent any Bean Validation annotation on a plain (non-validated) response record, springdoc
 * infers no {@code required} array at all. Leaving that to inference would have produced a
 * generated TypeScript client where every field here, none of which can ever actually be absent, is
 * optional.
 */
public record CapabilitiesResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String apiVersion,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String eventSchemaVersion,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<EnvironmentCapabilities> environments) {

  private static final String API_VERSION = "v1";

  public CapabilitiesResponse {
    Objects.requireNonNull(environments, "environments must not be null");
    environments = List.copyOf(environments);
  }

  public record EnvironmentCapabilities(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Environment name,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Suite> suites) {

    public EnvironmentCapabilities {
      Objects.requireNonNull(suites, "suites must not be null");
      suites = List.copyOf(suites);
    }
  }

  public static CapabilitiesResponse current() {
    List<EnvironmentCapabilities> environments =
        RunRequestValidator.allowedCombinations().entrySet().stream()
            .map(
                entry ->
                    new EnvironmentCapabilities(
                        entry.getKey(), entry.getValue().stream().sorted().toList()))
            .sorted((a, b) -> a.name().compareTo(b.name()))
            .toList();
    return new CapabilitiesResponse(API_VERSION, RunnerEvent.CURRENT_SCHEMA_VERSION, environments);
  }
}
