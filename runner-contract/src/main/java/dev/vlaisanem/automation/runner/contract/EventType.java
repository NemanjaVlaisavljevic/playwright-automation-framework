package dev.vlaisanem.automation.runner.contract;

/**
 * Event vocabulary, schema version 1.1: run-level, test-level, and step-level. The runner process
 * orchestrator owns {@code RUN_*} events, a JUnit {@code TestExecutionListener} produces {@code
 * TEST_*} events from execution start, finish, and skip callbacks, and the {@code Steps} API (main
 * automation suite) produces {@code STEP_*} events from inside a running test method. {@code
 * STEP_*} events are additive over the original schema 1.0 vocabulary: a test that never uses the
 * {@code Steps} API emits none of them. Every event in a run still carries the one current {@code
 * schemaVersion} - schema versions themselves never mix in one stream - what coexists is simply a
 * test using the {@code Steps} API alongside one that doesn't, both under that same version.
 *
 * <p>{@code RUN_QUEUED} is emitted once a submission is durably accepted. {@code RUN_STARTED} is
 * emitted only once the run's status has actually, successfully transitioned to {@code RUNNING} -
 * not merely once its OS process has launched - so a run whose lifecycle transition is lost to a
 * concurrent cancellation/error outcome right after launch never gets a {@code RUN_STARTED} it
 * cannot honestly back up. {@code RUN_FINISHED} is emitted exactly once for every run's terminal
 * outcome. A run cancelled while still queued therefore sees {@code RUN_QUEUED} followed directly
 * by {@code RUN_FINISHED}, with neither {@code RUN_STARTED} nor any {@code TEST_*} event in
 * between.
 */
public enum EventType {
  RUN_QUEUED(EventScope.RUN),
  RUN_STARTED(EventScope.RUN),
  RUN_FINISHED(EventScope.RUN),
  TEST_STARTED(EventScope.TEST),
  TEST_PASSED(EventScope.TEST),
  TEST_FAILED(EventScope.TEST),
  TEST_ABORTED(EventScope.TEST),
  TEST_SKIPPED(EventScope.TEST),
  STEP_STARTED(EventScope.STEP),
  STEP_PASSED(EventScope.STEP),
  STEP_FAILED(EventScope.STEP);

  private final EventScope scope;

  EventType(EventScope scope) {
    this.scope = scope;
  }

  public EventScope scope() {
    return scope;
  }

  /** True when the event describes one concrete test rather than the overall run. */
  public boolean isTestLevel() {
    return scope == EventScope.TEST;
  }

  /**
   * True when the event describes one step within a test rather than the test or run as a whole.
   */
  public boolean isStepLevel() {
    return scope == EventScope.STEP;
  }
}
