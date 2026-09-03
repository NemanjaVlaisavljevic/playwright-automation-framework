package dev.vlaisanem.automation.runner.service.orchestration;

import dev.vlaisanem.automation.runner.service.catalog.RunCatalog;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.exception.UnsupportedRunCombinationException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Rejects any (environment, suite) combination {@link RunCatalog} does not know a Gradle task for.
 * Deliberately a separate check from what {@link Environment}/{@link Suite} can even represent:
 * adding a new enum value must not silently make every existing request able to use it - {@link
 * RunCatalog} is the single place that actually turns a combination on, for both this validator and
 * {@code SuiteCommandFactory}.
 */
public final class RunRequestValidator {

  private RunRequestValidator() {}

  public static void validate(Environment environment, Suite suite) {
    if (RunCatalog.gradleTaskFor(environment, suite).isEmpty()) {
      throw new UnsupportedRunCombinationException(environment, suite);
    }
  }

  /**
   * The same allowlist {@link #validate} enforces, grouped by environment and exposed read-only (an
   * unmodifiable map of unmodifiable sets) so a capabilities endpoint can mirror exactly what the
   * server will actually accept instead of hand-copying it into a second, driftable list.
   */
  public static Map<Environment, Set<Suite>> allowedCombinations() {
    Map<Environment, Set<Suite>> grouped = new EnumMap<>(Environment.class);
    for (RunCatalog.Key key : RunCatalog.allowedKeys()) {
      grouped
          .computeIfAbsent(key.environment(), unused -> EnumSet.noneOf(Suite.class))
          .add(key.suite());
    }
    Map<Environment, Set<Suite>> immutable = new EnumMap<>(Environment.class);
    grouped.forEach((environment, suites) -> immutable.put(environment, Set.copyOf(suites)));
    return Map.copyOf(immutable);
  }
}
