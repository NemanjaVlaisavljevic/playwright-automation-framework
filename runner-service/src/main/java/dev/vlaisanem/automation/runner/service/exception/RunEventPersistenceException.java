package dev.vlaisanem.automation.runner.service.exception;

/**
 * Signals that the canonical event journal can no longer record a trustworthy lifecycle timeline.
 * The runner fails closed: the affected run is marked {@code ERROR} and new submissions are refused
 * until restart, rather than risking state the journal cannot reproduce.
 */
public class RunEventPersistenceException extends RuntimeException {

  public RunEventPersistenceException(String runId, Throwable cause) {
    super(
        "Canonical event journal is unavailable; run " + runId + " cannot be recorded safely",
        cause);
  }
}
