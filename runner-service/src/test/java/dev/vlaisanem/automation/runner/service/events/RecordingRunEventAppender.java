package dev.vlaisanem.automation.runner.service.events;

import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongFunction;
import org.slf4j.MDC;

/**
 * In-memory {@link RunEventAppender} for tests that exercise a lifecycle coordinator or service
 * without a real filesystem journal. Enforces the same sequence/runId-match and reject-after-
 * terminal invariants a real journal would, so a double-{@code RUN_FINISHED} or wrong-sequence bug
 * is still caught here.
 */
public final class RecordingRunEventAppender implements RunEventAppender {

  private final List<RunnerEvent> events = new CopyOnWriteArrayList<>();
  private final Set<String> closedRunIds = ConcurrentHashMap.newKeySet();
  private final Object lock = new Object();
  // Captured here instead of via a test-only field on production code: this is the exact call
  // ListenerEventIngestor's background thread makes for every forwarded event, so observing MDC
  // here proves runId is set on the thread that matters.
  private volatile String lastAppendMdcRunId;

  @Override
  public RunnerEvent append(String runId, LongFunction<RunnerEvent> eventFactory) {
    lastAppendMdcRunId = MDC.get("runId");
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

  /** The {@code runId} MDC held on whichever thread most recently called {@link #append}. */
  public String lastAppendMdcRunId() {
    return lastAppendMdcRunId;
  }
}
