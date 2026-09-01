package dev.vlaisanem.automation.runner.service.events;

/**
 * Thrown when {@link RunEventAppender#append} is refused because that run's canonical timeline is
 * already closed - whichever of several possible causes actually applies: a {@code RUN_FINISHED}
 * event was already recorded for it, an earlier write poisoned it, its in-memory entry was already
 * evicted and re-opening found an existing data file or completion marker on disk, or the whole
 * journal component has been shut down. Callers should never need to distinguish these - they all
 * mean exactly the same thing to {@link RunEventAppender}'s contract: this run's timeline cannot
 * accept another event. Extends {@link IllegalStateException} so existing callers written against
 * that contract keep working unchanged.
 */
public class RunEventJournalConflictException extends IllegalStateException {

  public RunEventJournalConflictException(String message) {
    super(message);
  }

  public RunEventJournalConflictException(String message, Throwable cause) {
    super(message, cause);
  }
}
