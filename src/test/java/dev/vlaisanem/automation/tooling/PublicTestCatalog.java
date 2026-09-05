package dev.vlaisanem.automation.tooling;

import java.util.List;

/** Wire shape of the generated catalog file - matches {@code GET /api/v1/tests}'s response body. */
public record PublicTestCatalog(List<PublicTestCatalogEntry> tests) {}
