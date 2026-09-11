package dev.vlaisanem.automation.runner.service.events;

/**
 * Thrown when {@link RunEventAppender#append} is refused because that run's canonical timeline is
 * already closed, for any of several possible causes. Callers never need to distinguish them - this
 * run's timeline simply can't accept another event. Extends {@link IllegalStateException} so
 * existing callers keep working unchanged.
 */
public class RunEventJournalConflictException extends IllegalStateException {

  public RunEventJournalConflictException(String message) {
    super(message);
  }

  public RunEventJournalConflictException(String message, Throwable cause) {
    super(message, cause);
  }
}
