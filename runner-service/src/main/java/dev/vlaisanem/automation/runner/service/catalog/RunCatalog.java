package dev.vlaisanem.automation.runner.service.catalog;

import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The single source of truth for which {@code (Environment, Suite)} combinations this service can
 * actually run, and which Gradle task each one maps to. Both {@code RunRequestValidator} (is this
 * combination allowed at all?) and {@code SuiteCommandFactory} (what Gradle task actually runs it?)
 * derive from this one map, so a combination can never be allowed without also being runnable, or
 * runnable without being allowed - previously two independently hand-maintained maps, a risk that
 * stopped being hypothetical the moment a second {@link Environment} value existed at all (until
 * {@link Environment#LOCAL}, every combination happened to differ only by {@link Suite}, so the two
 * maps could not yet drift in a way that actually mattered).
 *
 * <p>Deliberately not in the {@code domain} package alongside {@link Environment}/{@link Suite}: a
 * concrete Gradle task name (a build-tool/execution detail, not a domain concept the way an
 * environment or a suite is) belongs in its own package rather than making {@code domain} depend on
 * infrastructure it should otherwise stay ignorant of.
 *
 * <p>Each {@link Environment}/{@link Suite} pair gets its own dedicated Gradle task (e.g. {@code
 * journeyTest} for {@code PUBLIC}+{@code JOURNEY}, {@code localJourneyTest} for {@code
 * LOCAL}+{@code JOURNEY}) with everything about that environment - {@code baseUrl}, which tags it
 * does or doesn't exclude - already baked into the task definition itself in {@code build.gradle}.
 * That is deliberately why this catalog only needs to carry a task name, not a richer command spec:
 * there is no per-combination dynamic system property this process needs to inject beyond the
 * {@code runId}/ {@code rawEventsDir} pair every task already receives (see {@code
 * SuiteCommandFactory}).
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
