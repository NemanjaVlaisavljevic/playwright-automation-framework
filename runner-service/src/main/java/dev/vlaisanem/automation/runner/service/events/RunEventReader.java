package dev.vlaisanem.automation.runner.service.events;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import java.util.List;
import java.util.Optional;

/**
 * Read side of the canonical event journal - deliberately separate from {@link RunEventAppender}:
 * most callers (the lifecycle coordinator, the ingestor) only ever need to append, and a narrow,
 * single-purpose interface on each side keeps that true. {@link RunEventBroker} is the only caller
 * that needs both, to serve replay.
 */
public interface RunEventReader {

  /**
   * Every event recorded for {@code runId} with a sequence strictly greater than {@code
   * afterSequence}, in order. Empty if the run has no journal at all (never started, or the service
   * has since restarted) or nothing new has been recorded since {@code afterSequence}. Pass {@code
   * 0} to read the full history.
   */
  List<RunnerEvent> readAfter(String runId, long afterSequence);

  /**
   * The most recently recorded event for {@code runId}, or empty if none has been recorded at all.
   * Lets a caller validate a resume point against the actual current high-water mark, and detect a
   * terminal run (its type is {@code RUN_FINISHED}), without re-reading the full history just to
   * inspect its last element.
   */
  Optional<RunnerEvent> latest(String runId);
}
