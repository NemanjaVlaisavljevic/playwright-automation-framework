package dev.vlaisanem.automation.runner.service.events;

import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongFunction;

/**
 * In-memory {@link RunEventAppender} for tests that exercise a lifecycle coordinator or service
 * without standing up a real filesystem journal. Enforces the same "sequence must match", "runId
 * must match", and "reject further appends once terminal" invariants a real journal would, so a
 * test using this catches a genuine double-{@code RUN_FINISHED} or wrong-sequence bug exactly the
 * way {@code FileBackedRunEventJournalTest} does for the real implementation.
 */
public final class RecordingRunEventAppender implements RunEventAppender {

  private final List<RunnerEvent> events = new CopyOnWriteArrayList<>();
  private final Set<String> closedRunIds = ConcurrentHashMap.newKeySet();
  private final Object lock = new Object();

  @Override
  public RunnerEvent append(String runId, LongFunction<RunnerEvent> eventFactory) {
    synchronized (lock) {
      if (closedRunIds.contains(runId)) {
        throw new RunEventJournalConflictException(
            "Recording appender: run " + runId + " no longer accepts events");
      }
      long nextSequence = eventsFor(runId).size() + 1L;
      RunnerEvent event = eventFactory.apply(nextSequence);
      if (event.sequence() != nextSequence) {
        throw new IllegalArgumentException(
            "Event factory for run "
                + runId
                + " returned sequence "
                + event.sequence()
                + " but this appender assigned "
                + nextSequence);
      }
      if (!runId.equals(event.runId())) {
        throw new IllegalArgumentException(
            "Event runId " + event.runId() + " does not match appended runId " + runId);
      }
      events.add(event);
      if (event.type() == EventType.RUN_FINISHED) {
        closedRunIds.add(runId);
      }
      return event;
    }
  }

  public List<RunnerEvent> eventsFor(String runId) {
    return events.stream().filter(event -> event.runId().equals(runId)).toList();
  }

  /** Every event ever appended, across every runId - proves nothing was emitted at all. */
  public int totalEventCount() {
    return events.size();
  }
}
