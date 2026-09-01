package dev.vlaisanem.automation.runner.service.exception;

/**
 * Signals that the canonical event journal can no longer record a trustworthy lifecycle timeline.
 * The runner fails closed after this condition: the affected run is represented as {@code ERROR} in
 * the in-memory repository (when it had already been accepted), and new submissions are refused
 * until the service is restarted rather than risking REST state that the journal cannot reproduce.
 */
public class RunEventPersistenceException extends RuntimeException {

  public RunEventPersistenceException(String runId, Throwable cause) {
    super(
        "Canonical event journal is unavailable; run " + runId + " cannot be recorded safely",
        cause);
  }
}
