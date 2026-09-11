package dev.vlaisanem.automation.runner.service.events;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import java.util.function.LongFunction;

/**
 * Sole entry point for adding an event to a run's canonical, cross-run-lifecycle event timeline.
 * The implementation, not the caller, owns sequence assignment: {@code eventFactory} receives the
 * sequence number it must use, since assigning one a step before the actual write would let two
 * threads race between "take a number" and "append".
 *
 * <p>A narrow interface deliberately: production code depends on this, not the file-backed
 * implementation, so orchestration-level tests can substitute an in-memory recording appender.
 */
public interface RunEventAppender {

  /**
   * Appends one event for {@code runId}. Rejects the call (throwing {@link
   * RunEventJournalConflictException}) once that run's journal is already closed - a run's
   * canonical timeline may end in exactly one {@code RUN_FINISHED} and nothing may follow it,
   * regardless of the specific underlying cause (already terminal, poisoned by an earlier write
   * failure, or the journal component itself having been shut down).
   */
  RunnerEvent append(String runId, LongFunction<RunnerEvent> eventFactory);
}
