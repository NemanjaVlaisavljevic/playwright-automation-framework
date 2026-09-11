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
 * Rejects any (environment, suite) combination {@link RunAvailabilityPolicy} does not allow. Kept
 * separate from what {@link Environment}/{@link Suite} can represent, so a new enum value doesn't
 * silently become usable everywhere: {@link RunCatalog} and {@link RunAvailabilityPolicy} are the
 * only places that actually turn a combination on.
 */
public final class RunRequestValidator {

  private RunRequestValidator() {}

  public static void validate(RunAvailabilityPolicy policy, Environment environment, Suite suite) {
    if (!policy.allows(environment, suite)) {
      throw new UnsupportedRunCombinationException(environment, suite);
    }
  }

  /**
   * The same allowlist {@link #validate} enforces, grouped by environment and read-only, so a
   * capabilities endpoint can mirror it instead of hand-copying a second, driftable list.
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
