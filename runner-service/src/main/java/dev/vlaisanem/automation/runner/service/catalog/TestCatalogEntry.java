package dev.vlaisanem.automation.runner.service.catalog;

import dev.vlaisanem.automation.runner.service.domain.TestLayer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import java.util.Set;

/**
 * One selectable {@code CUSTOM}-run test - both the wire shape of {@code GET /api/v1/tests} and
 * what the committed catalog file on disk deserializes into (see {@link TestCatalogService}).
 * {@code testKey} is deliberately not called {@code testId} - the SSE/event contract's {@code
 * testId} is a JUnit {@code UniqueId}; this is the separate, much simpler {@code
 * ClassName#methodName} identity the main suite's own {@code TestCatalogGenerator} produces.
 */
public record TestCatalogEntry(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String testKey,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TestLayer category,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<String> tags) {

  public TestCatalogEntry {
    if (testKey == null || testKey.isBlank()) {
      throw new IllegalArgumentException("testKey must not be blank");
    }
    if (displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException("displayName must not be blank");
    }
    Objects.requireNonNull(category, "category must not be null");
    Objects.requireNonNull(tags, "tags must not be null");
    tags = Set.copyOf(tags);
  }
}
