package dev.vlaisanem.automation.runner.service.domain;

/**
 * Allowlisted target environments a run can execute against. Only {@link #PUBLIC} exists for now
 * (MVP scope) - a future {@code LOCAL} value (the Docker-based Restful Booker Platform stack) will
 * be added together with its allowlist entry ({@code RunRequestValidator}) and Gradle task mapping
 * ({@code SuiteCommandFactory}) in one deliberate change, not pre-declared here unused.
 */
public enum Environment {
  PUBLIC
}
