package dev.vlaisanem.automation.runner.service.domain;

/** Lifecycle status of a run. See {@link RunStateMachine} for the allowed transitions. */
public enum RunStatus {
  QUEUED,
  STARTING,
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED,
  TIMED_OUT,
  ERROR;

  /** True once no further transition is possible - see {@link RunStateMachine}. */
  public boolean isTerminal() {
    return this == SUCCEEDED
        || this == FAILED
        || this == CANCELLED
        || this == TIMED_OUT
        || this == ERROR;
  }
}
