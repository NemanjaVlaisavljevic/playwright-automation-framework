package dev.vlaisanem.automation.runner.contract;

import java.util.UUID;

/**
 * The one shared source of truth for "what run is this JVM part of?", resolved once per JVM at
 * class-load time so every consumer in that process agrees on the same value, however it was
 * launched (Gradle {@code Test} task, IDE runner, bare {@code java}). {@code TestConfig} and {@code
 * RunnerEventTestExecutionListener} both resolve through this class rather than each generating
 * their own fallback UUID, which would break the {@code runId} correlation between a manifest and
 * its {@link RunnerEvent}s.
 *
 * <p>Resolution order: {@code runner.runId} system property, then {@code RUNNER_RUN_ID} env var (a
 * forked JVM inherits env vars automatically but not {@code -D} properties unless build.gradle
 * forwards them), then a fresh {@code local-<UUID>} fallback. Resolved here rather than in a build
 * script closure so the fallback never becomes a Gradle {@code Test} task input, which would defeat
 * build-cache/up-to-date checking on every invocation.
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

  /** Pure (takes inputs directly) so a test can exercise every branch deterministically. */
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
