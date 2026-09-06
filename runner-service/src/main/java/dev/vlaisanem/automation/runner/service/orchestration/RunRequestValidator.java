package dev.vlaisanem.automation.runner.service.orchestration;

import dev.vlaisanem.automation.runner.service.catalog.RunAvailabilityPolicy;
import dev.vlaisanem.automation.runner.service.catalog.RunCatalog;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.exception.UnsupportedRunCombinationException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Rejects any (environment, suite) combination {@link RunAvailabilityPolicy} does not allow.
 * Deliberately a separate check from what {@link Environment}/{@link Suite} can even represent:
 * adding a new enum value must not silently make every existing request able to use it - {@link
 * RunCatalog} (what this codebase can run at all) and {@link RunAvailabilityPolicy} (what this
 * deployment currently allows of that) are the only two places that actually turn a combination on,
 * for this validator, {@code SuiteCommandFactory}, and {@code CapabilitiesResponse} alike.
 */
public final class RunRequestValidator {

  private RunRequestValidator() {}

  public static void validate(RunAvailabilityPolicy policy, Environment environment, Suite suite) {
    if (!policy.allows(environment, suite)) {
      throw new UnsupportedRunCombinationException(environment, suite);
    }
  }

  /**
   * The same allowlist {@link #validate} enforces, grouped by environment and exposed read-only (an
   * unmodifiable map of unmodifiable sets) so a capabilities endpoint can mirror exactly what the
   * server will actually accept instead of hand-copying it into a second, driftable list.
   */
  public static Map<Environment, Set<Suite>> allowedCombinations(RunAvailabilityPolicy policy) {
    Map<Environment, Set<Suite>> grouped = new EnumMap<>(Environment.class);
    for (RunCatalog.Key key : policy.allowedKeys()) {
      grouped
          .computeIfAbsent(key.environment(), unused -> EnumSet.noneOf(Suite.class))
          .add(key.suite());
    }
    Map<Environment, Set<Suite>> immutable = new EnumMap<>(Environment.class);
    grouped.forEach((environment, suites) -> immutable.put(environment, Set.copyOf(suites)));
    return Map.copyOf(immutable);
  }
}
