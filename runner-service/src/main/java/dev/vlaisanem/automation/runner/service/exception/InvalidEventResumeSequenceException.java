package dev.vlaisanem.automation.runner.service.exception;

/**
 * Thrown when a client's {@code Last-Event-ID} is ahead of the sequence the canonical journal has
 * actually produced for that run. Resuming from it would silently skip events the client never saw,
 * so it is rejected rather than tolerated.
 */
public class InvalidEventResumeSequenceException extends RuntimeException {

  public InvalidEventResumeSequenceException(
      String runId, long requestedAfterSequence, long latestSequence) {
    super(
        "Last-Event-ID "
            + requestedAfterSequence
            + " for run "
            + runId
            + " is ahead of the canonical journal's current sequence ("
            + latestSequence
            + ")");
  }
}
