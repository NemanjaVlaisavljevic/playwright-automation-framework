package dev.vlaisanem.automation.runner.service.domain;

/**
 * Allowlisted test suites a run can execute. Each maps to exactly one fixed Gradle task - see
 * {@code SuiteCommandFactory} - the REST API never accepts a task name, tag, or shell argument
 * directly.
 *
 * <p>{@code FIXTURE} runs exactly one test - a deliberately always-failing fixture - to exercise
 * the dashboard's step drill-down UI without depending on the shared public app misbehaving.
 *
 * <p>{@code CUSTOM} is the one suite whose Gradle invocation is not fully static: a request
 * additionally carries a client-chosen list of {@code testKey}s, validated against the
 * server-generated catalog before ever reaching a process argument. {@code PUBLIC} only.
 */
public enum Suite {
  SMOKE,
  API,
  UI,
  JOURNEY,
  REGRESSION,
  FIXTURE,
  CUSTOM
}
