package dev.vlaisanem.automation.runner.service.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;

/** Wire representation of {@code GET /api/v1/tests}. */
public record TestCatalogResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<TestCatalogEntry> tests) {

  public TestCatalogResponse {
    Objects.requireNonNull(tests, "tests must not be null");
    tests = List.copyOf(tests);
  }
}
