package dev.vlaisanem.automation.runner.service.events;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import java.util.function.LongFunction;

/**
 * Sole entry point for adding an event to a run's canonical, cross-run-lifecycle event timeline.
 * The implementation - not the caller - owns sequence assignment: {@code eventFactory} receives the
 * sequence number it must use, mirroring runner-listener's {@code RunnerEventJsonlWriter}, for the
 * same reason - assigning a sequence number a step before the actual write would let two threads
 * race between "take a number" and "append", so a lower sequence number could land after a higher
 * one.
 *
 * <p>A narrow interface deliberately: production code depends on this, not on the file-backed
 * implementation directly, so orchestration-level tests can substitute an in-memory recording
 * appender instead of standing up a real filesystem journal.
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
