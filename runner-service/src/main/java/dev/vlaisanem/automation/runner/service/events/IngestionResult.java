package dev.vlaisanem.automation.runner.service.events;

/**
 * Outcome of one {@link ListenerEventIngestor#stopAndAwaitFinished} call.
 *
 * @param valid {@code false} only on a genuine validation or I/O failure (malformed JSON, a
 *     source-sequence gap/duplicate, a wrong runId, or an unexpected read failure) - the run must
 *     then end as {@code ERROR} regardless of its process exit code.
 * @param sawCompletionMarker whether the raw {@code .tests.complete} marker was observed before
 *     this ingestor stopped. {@code false} is expected for a cancelled/timed-out run; otherwise it
 *     means the exit code alone can't be trusted.
 * @param detail present only when {@code valid} is {@code false}; a short, structured description
 *     of what failed - never the raw offending line (unbounded size, potentially sensitive output).
 */
public record IngestionResult(boolean valid, boolean sawCompletionMarker, String detail) {

  public static IngestionResult valid(boolean sawCompletionMarker) {
    return new IngestionResult(true, sawCompletionMarker, null);
  }

  public static IngestionResult invalid(String detail) {
    return new IngestionResult(false, false, detail);
  }
}
