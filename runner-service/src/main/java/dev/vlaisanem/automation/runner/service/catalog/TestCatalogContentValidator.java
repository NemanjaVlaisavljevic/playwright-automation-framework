package dev.vlaisanem.automation.runner.service.catalog;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a deserialized catalog before {@link TestCatalogService} ever exposes it. The catalog
 * file ships with the repository and {@code testCatalogCheck} (root {@code build.gradle}) plus
 * {@code TestCatalogGenerator} (main test suite) already guard it at generation time - but {@link
 * TestCatalogService} re-reads the file fresh off disk on every call in a deployed process, and
 * must treat it as untrusted input, not an inherently safe artifact: a corrupted or hand-edited
 * file could otherwise silently change what {@link
 * dev.vlaisanem.automation.runner.service.orchestration.CustomTestSelectionValidator} accepts as a
 * legal {@code --tests} filter. Mirrors the same checks the generator enforces, applied again here
 * at load time.
 */
final class TestCatalogContentValidator {

  private static final Set<String> LAYER_TAGS = Set.of("api", "ui", "journey");

  private TestCatalogContentValidator() {}

  static void validate(List<TestCatalogEntry> entries) {
    if (entries.isEmpty()) {
      throw new IllegalStateException("Test catalog has no entries.");
    }

    Set<String> seenTestKeys = new HashSet<>();
    for (TestCatalogEntry entry : entries) {
      String testKey = entry.testKey();
      if (!seenTestKeys.add(testKey)) {
        throw new IllegalStateException("Test catalog has a duplicate testKey: " + testKey);
      }
      if (testKey.contains("*") || testKey.contains("?")) {
        throw new IllegalStateException(
            "Test catalog entry has a non-canonical testKey (contains a wildcard): " + testKey);
      }

      Set<String> tags = entry.tags();
      requireTag(testKey, tags, "regression");
      requireTag(testKey, tags, "read-only");
      forbidTag(testKey, tags, "mutation");
      forbidTag(testKey, tags, "fixture");

      long layerTagCount = LAYER_TAGS.stream().filter(tags::contains).count();
      if (layerTagCount != 1) {
        throw new IllegalStateException(
            "Test catalog entry '"
                + testKey
                + "' has "
                + layerTagCount
                + " of the api/ui/journey layer tags (expected exactly 1): "
                + tags);
      }
    }
  }

  private static void requireTag(String testKey, Set<String> tags, String tag) {
    if (!tags.contains(tag)) {
      throw new IllegalStateException(
          "Test catalog entry '" + testKey + "' is missing the required '" + tag + "' tag.");
    }
  }

  private static void forbidTag(String testKey, Set<String> tags, String tag) {
    if (tags.contains(tag)) {
      throw new IllegalStateException(
          "Test catalog entry '" + testKey + "' carries the forbidden '" + tag + "' tag.");
    }
  }
}
