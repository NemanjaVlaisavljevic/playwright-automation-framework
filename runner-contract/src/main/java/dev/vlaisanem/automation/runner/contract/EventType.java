package dev.vlaisanem.automation.runner.contract;

/**
 * Event vocabulary, schema 1.1: run-level ({@code RUN_*}), test-level ({@code TEST_*}), and
 * step-level ({@code STEP_*}, additive from the {@code Steps} API - unused by tests that don't call
 * it). {@code RUN_STARTED} fires only once a run's status actually reaches {@code RUNNING}, not
 * merely once its process launches: a run cancelled while queued emits {@code RUN_QUEUED} then
 * {@code RUN_FINISHED} directly, with no {@code RUN_STARTED}/{@code TEST_*} in between.
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
