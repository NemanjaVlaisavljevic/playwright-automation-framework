package dev.vlaisanem.automation.runner.service.exception;

/**
 * Thrown when a request reaches the submit or SSE-subscribe endpoint before {@code
 * RunRecoveryService}'s one-time startup recovery pass has finished (see
 * docs/DEPLOYMENT_ARCHITECTURE.md "Restart behavior"). Recovery must complete before the service
 * accepts traffic, so a client can't act on a stale run still being rewritten to {@code ERROR}.
 */
public class RunnerRecoveringException extends RuntimeException {

  public RunnerRecoveringException() {
    super(
        "Runner is still recovering non-terminal runs from a previous restart; try again"
            + " shortly.");
  }
}
