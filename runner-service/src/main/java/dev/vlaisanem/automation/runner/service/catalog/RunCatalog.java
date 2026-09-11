package dev.vlaisanem.automation.runner.service.catalog;

import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The single source of truth for which {@code (Environment, Suite)} combinations this service can
 * run, and which Gradle task each one maps to. Both {@code RunRequestValidator} and {@code
 * SuiteCommandFactory} derive from this one map, so a combination can never be allowed without also
 * being runnable, or vice versa.
 *
 * <p>Not in the {@code domain} package alongside {@link Environment}/{@link Suite}: a concrete
 * Gradle task name is a build-tool detail, not a domain concept.
 *
 * <p>Each pair's task already has everything about that environment - {@code baseUrl}, tag
 * exclusions - baked into its {@code build.gradle} definition, so this catalog only needs to carry
 * a task name, not a richer command spec.
 */
public final class RunCatalog {

  public record Key(Environment environment, Suite suite) {}

  private static final Map<Key, String> GRADLE_TASK_BY_KEY =
      Map.ofEntries(
          Map.entry(new Key(Environment.PUBLIC, Suite.SMOKE), "smokeTest"),
          Map.entry(new Key(Environment.PUBLIC, Suite.API), "apiTest"),
          Map.entry(new Key(Environment.PUBLIC, Suite.UI), "uiTest"),
          Map.entry(new Key(Environment.PUBLIC, Suite.JOURNEY), "journeyTest"),
          Map.entry(new Key(Environment.PUBLIC, Suite.REGRESSION), "regressionTest"),
          Map.entry(new Key(Environment.PUBLIC, Suite.FIXTURE), "fixtureTest"),
          Map.entry(new Key(Environment.PUBLIC, Suite.CUSTOM), "customTest"),
          Map.entry(new Key(Environment.LOCAL, Suite.JOURNEY), "localJourneyTest"));

  private RunCatalog() {}

  /** The Gradle task for this combination, or empty if it is not allowed at all. */
  public static Optional<String> gradleTaskFor(Environment environment, Suite suite) {
    return Optional.ofNullable(GRADLE_TASK_BY_KEY.get(new Key(environment, suite)));
  }

  /**
   * Every allowed combination - the raw material {@code RunRequestValidator} groups by environment.
   */
  public static Set<Key> allowedKeys() {
    return GRADLE_TASK_BY_KEY.keySet();
  }
}
