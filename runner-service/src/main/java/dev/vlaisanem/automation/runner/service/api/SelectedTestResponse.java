package dev.vlaisanem.automation.runner.service.api;

import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.TestLayer;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Wire representation of a {@link SelectedTestSnapshot} - kept separate from the domain record
 * (mirroring {@link RunResponse} itself) so a future change to the domain snapshot shape (e.g. once
 * D2 ties it to a Postgres {@code run_selected_tests} row) does not silently change the
 * REST/OpenAPI contract or the generated TypeScript client.
 */
public record SelectedTestResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String testKey,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TestLayer layer) {

  public static SelectedTestResponse from(SelectedTestSnapshot snapshot) {
    return new SelectedTestResponse(snapshot.testKey(), snapshot.displayName(), snapshot.layer());
  }
}
