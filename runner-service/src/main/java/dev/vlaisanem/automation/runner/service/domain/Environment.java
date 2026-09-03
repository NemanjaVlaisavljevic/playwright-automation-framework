package dev.vlaisanem.automation.runner.service.domain;

/**
 * Allowlisted target environments a run can execute against.
 *
 * <p>{@link #LOCAL} is the Docker-based Restful Booker Platform stack ({@code infra/rbp/}) - the
 * runner deliberately never starts, stops, or otherwise manages that stack's lifecycle itself (see
 * {@code localSutVerifyRunning} in the root {@code build.gradle}, which only checks health and
 * fails fast if the stack isn't already up); a developer brings it up by hand with {@code
 * ./gradlew.bat localSutUp} first. This keeps Docker Compose lifecycle races entirely out of the
 * runner process, and - together with {@code RunCatalog} only ever mapping {@code LOCAL} to a
 * dedicated Gradle task with {@code baseUrl} baked in as {@code http://localhost} - means a {@code
 * LOCAL} run can safely include {@code mutation}-tagged tests without ever writing to the shared
 * public target ({@code TestConfig#targetsSharedEnvironment()} naturally returns {@code false} for
 * that origin, so {@code AutomationExtension}'s mutation guard passes with no explicit opt-in).
 */
public enum Environment {
  PUBLIC,
  LOCAL
}
