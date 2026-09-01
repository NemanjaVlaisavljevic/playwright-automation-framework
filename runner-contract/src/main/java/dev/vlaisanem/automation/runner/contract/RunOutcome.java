package dev.vlaisanem.automation.runner.contract;

/** Terminal outcome of a runner process, required only by {@link EventType#RUN_FINISHED}. */
public enum RunOutcome {
  /** The test process completed successfully. */
  SUCCEEDED,

  /** The test process completed with an unsuccessful result. */
  FAILED,

  /** A user or runner request cancelled the test process. */
  CANCELLED,

  /** The test process exceeded its configured deadline. */
  TIMED_OUT,

  /** Runner infrastructure could not start, monitor, or complete the test process. */
  ERROR
}
