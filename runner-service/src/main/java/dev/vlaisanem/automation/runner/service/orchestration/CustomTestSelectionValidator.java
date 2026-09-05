package dev.vlaisanem.automation.runner.service.orchestration;

import dev.vlaisanem.automation.runner.service.catalog.TestCatalogEntry;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one place a client-submitted {@code testKey} list gets turned into a trustworthy {@link
 * SelectedTestSnapshot} list - every rule here exists specifically so a {@code CUSTOM} request can
 * never reach a Gradle process argument with anything beyond what the server's own catalog already
 * allowed (see {@code SuiteCommandFactory}, which only ever consumes the output of this class, not
 * the raw request body).
 */
public final class CustomTestSelectionValidator {

  /** Never let a single request select more of the catalog than this, even if it is smaller. */
  private static final int MAX_SELECTABLE = 25;

  private CustomTestSelectionValidator() {}

  public static List<SelectedTestSnapshot> validate(
      Suite suite, List<String> testKeys, List<TestCatalogEntry> catalog) {
    boolean isCustom = suite == Suite.CUSTOM;
    List<String> requested = testKeys == null ? List.of() : testKeys;

    if (!isCustom) {
      if (!requested.isEmpty()) {
        throw new InvalidTestSelectionException(suite + " must not carry testKeys");
      }
      return List.of();
    }

    if (requested.isEmpty()) {
      throw new InvalidTestSelectionException("CUSTOM requires at least one testKey");
    }

    Set<String> seen = new HashSet<>();
    for (String key : requested) {
      if (!seen.add(key)) {
        throw new InvalidTestSelectionException("Duplicate testKey: " + key);
      }
    }

    int max = Math.min(MAX_SELECTABLE, catalog.size());
    if (requested.size() > max) {
      throw new InvalidTestSelectionException(
          "At most " + max + " test(s) may be selected, got " + requested.size());
    }

    // TestCatalogContentValidator already guards against a duplicate testKey reaching this method
    // in production (see TestCatalogService), but this method also takes `catalog` as a plain
    // parameter with no guarantee its caller ran that check - silently keeping only the last
    // duplicate here would be exactly the kind of hidden collision the whole catalog design exists
    // to prevent, so fail fast instead of trusting the input.
    Map<String, TestCatalogEntry> byKey = new LinkedHashMap<>();
    for (TestCatalogEntry entry : catalog) {
      if (byKey.putIfAbsent(entry.testKey(), entry) != null) {
        throw new IllegalStateException("Test catalog has a duplicate testKey: " + entry.testKey());
      }
    }

    List<SelectedTestSnapshot> selected = new ArrayList<>();
    for (String key : requested) {
      TestCatalogEntry entry = byKey.get(key);
      if (entry == null) {
        throw new InvalidTestSelectionException("Unknown or stale testKey: " + key);
      }
      selected.add(
          new SelectedTestSnapshot(entry.testKey(), entry.displayName(), entry.category()));
    }
    return List.copyOf(selected);
  }
}
