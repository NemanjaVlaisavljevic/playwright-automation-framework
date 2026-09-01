package dev.vlaisanem.automation.runner.service.events;

/**
 * Outcome of one {@link ListenerEventIngestor#stopAndAwaitFinished} call.
 *
 * @param valid {@code false} only when a genuine validation or I/O failure occurred (malformed
 *     JSON, a source-sequence gap/duplicate, a wrong runId, or an unexpected read failure) - the
 *     canonical timeline forwarded so far cannot be trusted and the run must end as {@code ERROR}
 *     regardless of its process exit code.
 * @param sawCompletionMarker whether the raw {@code .tests.complete} marker was observed before
 *     this ingestor stopped. {@code false} is expected and tolerated for a run that was cancelled
 *     or timed out (the JVM may have been killed before the listener closed its writer); for a run
 *     that otherwise completed normally, it means the same thing a missing marker always has meant
 *     in this service - the exit code alone cannot be trusted.
 * @param detail present only when {@code valid} is {@code false}; a short, structured description
 *     of what failed - never the raw offending line itself (unbounded size, potentially sensitive
 *     test output).
 */
public record IngestionResult(boolean valid, boolean sawCompletionMarker, String detail) {

  public static IngestionResult valid(boolean sawCompletionMarker) {
    return new IngestionResult(true, sawCompletionMarker, null);
  }

  public static IngestionResult invalid(String detail) {
    return new IngestionResult(false, false, detail);
  }
}
