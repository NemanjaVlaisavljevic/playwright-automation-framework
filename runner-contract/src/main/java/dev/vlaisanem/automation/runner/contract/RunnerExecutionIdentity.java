package dev.vlaisanem.automation.runner.contract;

import java.util.UUID;

/**
 * The one shared source of truth for "what run is this JVM part of?", resolved exactly once per JVM
 * process (a plain static field, initialized at class-load time - not a Gradle build script
 * closure) so every consumer sharing that process always agrees on the same value, however the JVM
 * itself was launched: a Gradle {@code Test} task, an IDE's own JUnit runner, or a bare {@code
 * java} invocation. The main automation framework's {@code TestConfig} and runner-listener's {@code
 * RunnerEventTestExecutionListener} both resolve through this one class rather than each
 * independently generating their own fallback - two different random UUIDs for the same run would
 * silently break {@link ArtifactManifestEntry}'s own contract that a manifest entry's {@code runId}
 * always matches its run's {@link RunnerEvent}s.
 *
 * <p>Resolution order: the {@code runner.runId} system property (set directly, or forwarded into a
 * forked Test JVM by build.gradle when the runner-service passed {@code -Drunner.runId} to the
 * Gradle invocation itself - forked JVMs do not inherit {@code -D} system properties
 * automatically), then the {@code RUNNER_RUN_ID} environment variable (environment variables ARE
 * inherited automatically by a forked JVM, so this reaches one without any build.gradle forwarding
 * at all), then a fresh {@code local-<UUID>} fallback for an unmanaged run - nobody correlates a
 * manifest against an event stream in that case anyway. Resolving this fallback here, in the JVM
 * itself, rather than in a build script closure, also means a random value is never read into a
 * Gradle {@code Test} task's own inputs (systemProperty), which would otherwise defeat build-cache/
 * up-to-date checking for that task on every single invocation.
 */
public final class RunnerExecutionIdentity {

  private static final String RUN_ID_PROPERTY = "runner.runId";
  private static final String RUN_ID_ENV = "RUNNER_RUN_ID";

  private static final String RUN_ID = resolve();

  private RunnerExecutionIdentity() {}

  public static String currentRunId() {
    return RUN_ID;
  }

  private static String resolve() {
    return resolve(System.getProperty(RUN_ID_PROPERTY), System.getenv(RUN_ID_ENV));
  }

  /**
   * Pure (reads neither {@code System.getProperty} nor {@code System.getenv} itself) specifically
   * so a test can exercise every branch deterministically - {@link #RUN_ID} is cached once at
   * class-load time and can never be re-resolved afterward within the same JVM, so mutating the
   * real system property/environment from a test could never reach it anyway.
   */
  static String resolve(String property, String environment) {
    if (property != null && !property.isBlank()) {
      return property.trim();
    }
    if (environment != null && !environment.isBlank()) {
      return environment.trim();
    }
    return "local-" + UUID.randomUUID();
  }
}
