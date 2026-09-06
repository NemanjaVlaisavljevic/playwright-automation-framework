package dev.vlaisanem.automation.runner.service.exception;

/**
 * Thrown when a request reaches {@code POST /api/v1/runs} or the SSE subscribe endpoint before
 * {@code RunRecoveryService}'s one-time startup recovery pass has finished - see
 * docs/DEPLOYMENT_ARCHITECTURE.md's "Restart behavior" section: recovery must complete before the
 * service accepts any traffic, not run as a background task after startup, so a client can never
 * submit a new run or subscribe to one while a stale, still-{@code RUNNING}-looking run from before
 * the restart is still being rewritten to {@code ERROR} underneath it.
 */
public class RunnerRecoveringException extends RuntimeException {

  public RunnerRecoveringException() {
    super(
        "Runner is still recovering non-terminal runs from a previous restart; try again"
            + " shortly.");
  }
}
