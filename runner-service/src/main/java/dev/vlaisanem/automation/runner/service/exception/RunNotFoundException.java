package dev.vlaisanem.automation.runner.service.exception;

/** Thrown when no run exists for a given runId. */
public class RunNotFoundException extends RuntimeException {

  public RunNotFoundException(String runId) {
    super("No run found for runId: " + runId);
  }
}
