package dev.vlaisanem.automation.runner.service.api;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.catalog.RunAvailabilityPolicy;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.orchestration.RunRequestValidator;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;

/**
 * Wire representation of what a client is actually allowed to submit, mirroring {@link
 * RunRequestValidator#allowedCombinations(RunAvailabilityPolicy)} so the frontend never hand-copies
 * the allowlist.
 *
 * <p>Both this record and {@link EnvironmentCapabilities} copy their list components in their
 * compact constructors, keeping the response deeply immutable regardless of how a caller builds
 * one.
 *
 * <p>Every component carries an explicit {@code requiredMode}: springdoc infers no {@code required}
 * array at all for a plain (non-validated) response record, which would otherwise make every field
 * optional in the generated TypeScript client.
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

  public static CapabilitiesResponse current(RunAvailabilityPolicy policy) {
    List<EnvironmentCapabilities> environments =
        RunRequestValidator.allowedCombinations(policy).entrySet().stream()
            .map(
                entry ->
                    new EnvironmentCapabilities(
                        entry.getKey(), entry.getValue().stream().sorted().toList()))
            .sorted((a, b) -> a.name().compareTo(b.name()))
            .toList();
    return new CapabilitiesResponse(API_VERSION, RunnerEvent.CURRENT_SCHEMA_VERSION, environments);
  }
}
