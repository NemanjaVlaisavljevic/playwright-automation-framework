package dev.vlaisanem.automation.runner.service.exception;

/**
 * Thrown when a client's {@code Last-Event-ID} claims to have already seen an event with a sequence
 * number the canonical journal never produced for that run. Resuming from it anyway would silently
 * skip ahead of whatever the client actually saw - or, for a run the journal has no record of at
 * all, replay nothing while still accepting an arbitrary claimed position - so this is rejected
 * outright rather than tolerated.
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
