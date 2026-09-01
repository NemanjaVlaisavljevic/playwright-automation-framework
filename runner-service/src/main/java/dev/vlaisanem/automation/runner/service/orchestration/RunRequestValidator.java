package dev.vlaisanem.automation.runner.service.orchestration;

import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.exception.UnsupportedRunCombinationException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Rejects any (environment, suite) combination not explicitly allowlisted here. Deliberately a
 * separate check from what {@link Environment}/{@link Suite} can even represent: adding a new enum
 * value later (e.g. a future {@code LOCAL} environment, or a new {@code Suite}) must not silently
 * make every existing request able to use it - this allowlist map is the single place that actually
 * turns a combination on. Each entry is spelled out explicitly rather than {@code
 * EnumSet.allOf(Suite.class)}, so a future suite (e.g. a LOCAL-only {@code FULL}) does not
 * automatically become valid under PUBLIC just by existing in the enum.
 */
public final class RunRequestValidator {

  private static final Map<Environment, Set<Suite>> ALLOWED_COMBINATIONS =
      Map.of(
          Environment.PUBLIC,
          Set.copyOf(
              EnumSet.of(Suite.SMOKE, Suite.API, Suite.UI, Suite.JOURNEY, Suite.REGRESSION)));

  private RunRequestValidator() {}

  public static void validate(Environment environment, Suite suite) {
    if (!ALLOWED_COMBINATIONS.getOrDefault(environment, Set.of()).contains(suite)) {
      throw new UnsupportedRunCombinationException(environment, suite);
    }
  }

  /**
   * The same allowlist {@link #validate} enforces, exposed read-only (an unmodifiable map of
   * unmodifiable sets, per {@link Set#copyOf}) so a capabilities endpoint can mirror exactly what
   * the server will actually accept instead of hand-copying it into a second, driftable list.
   */
  public static Map<Environment, Set<Suite>> allowedCombinations() {
    return ALLOWED_COMBINATIONS;
  }
}
