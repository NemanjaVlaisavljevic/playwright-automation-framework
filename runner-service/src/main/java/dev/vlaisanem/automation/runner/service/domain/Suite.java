package dev.vlaisanem.automation.runner.service.domain;

/**
 * Allowlisted test suites a run can execute. Each maps to exactly one fixed Gradle task - see
 * {@code SuiteCommandFactory} - the REST API never accepts a task name, tag, or shell argument
 * directly.
 */
public enum Suite {
  SMOKE,
  API,
  UI,
  JOURNEY,
  REGRESSION
}
