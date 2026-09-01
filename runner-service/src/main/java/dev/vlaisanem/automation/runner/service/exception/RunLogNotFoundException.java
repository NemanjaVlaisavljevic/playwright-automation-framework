package dev.vlaisanem.automation.runner.service.exception;

/** Thrown when a run exists but its process log has not been created (or retained). */
public class RunLogNotFoundException extends RuntimeException {

  public RunLogNotFoundException(String runId) {
    super("No process log is available for runId: " + runId);
  }
}
