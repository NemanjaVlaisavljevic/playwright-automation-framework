package dev.vlaisanem.automation.runner.service.domain;

/**
 * Allowlisted test suites a run can execute. Each maps to exactly one fixed Gradle task - see
 * {@code SuiteCommandFactory} - the REST API never accepts a task name, tag, or shell argument
 * directly.
 *
 * <p>{@code FIXTURE} runs exactly one test - the deliberately, always-failing step/failure/artifact
 * drill-down fixture (Faza B) - to exercise the dashboard's step drill-down UI against a real run
 * without depending on the shared public app ever actually misbehaving.
 *
 * <p>{@code CUSTOM} (D0.5) is the one suite whose Gradle invocation is not fully static - a request
 * additionally carries a client-chosen list of {@code testKey}s from {@code GET /api/v1/tests},
 * validated against that same server-generated catalog before ever reaching a process argument (see
 * {@code CreateRunRequest#testKeys}, {@code Run#selectedTests}, {@code SuiteCommandFactory}).
 * {@code PUBLIC} only - see {@code RunCatalog}.
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
