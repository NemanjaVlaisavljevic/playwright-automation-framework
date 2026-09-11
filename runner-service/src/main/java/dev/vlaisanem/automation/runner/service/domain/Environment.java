package dev.vlaisanem.automation.runner.service.domain;

/**
 * Allowlisted target environments a run can execute against.
 *
 * <p>{@link #LOCAL} is the Docker-based Restful Booker Platform stack ({@code infra/rbp/}); the
 * runner never starts, stops, or manages that stack's lifecycle itself - a developer brings it up
 * by hand with {@code ./gradlew.bat localSutUp} first. Its dedicated Gradle task bakes {@code
 * baseUrl} in as {@code http://localhost}, so a {@code LOCAL} run can safely include {@code
 * mutation}-tagged tests without ever writing to the shared public target.
 */
public enum Environment {
  PUBLIC,
  LOCAL
}
