package dev.vlaisanem.automation.runner.service.events;

import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.exception.InvalidEventResumeSequenceException;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The single entry point for both writing and subscribing to a run's canonical event timeline.
 * Internally owns a {@link RunEventReader}/{@link RunEventAppender} (the canonical journal) and a
 * {@link RunEventHub} (live fan-out) - callers never touch either directly, and the journal itself
 * has no notion of subscribers at all.
 *
 * <p>{@code @Primary} for {@link RunEventAppender}: {@link dev.vlaisanem.automation.runner.service
 * .orchestration.RunLifecycleCoordinator} and {@code ListenerEventIngestorFactory} depend only on
 * that narrow interface, so wiring them to this broker instead of the raw journal - with no changes
 * to either class - is what makes every canonical event they record also reach live subscribers.
 *
 * <p>A single per-run lock (see {@link #lockFor}) is the crux of this class: {@link #append} takes
 * it around "write to the journal, then publish the result to the hub", and {@link
 * #replayAndSubscribe} takes it around "read the replay snapshot from the journal, then register
 * the subscriber". Serializing those two operations against each other for the same run is what
 * guarantees a subscriber's replay batch and the live events that follow it are gapless and
 * duplicate-free - a live event can only be published either strictly before the replay snapshot is
 * taken (so it is included in the replay) or strictly after the subscriber is registered (so it
 * arrives live), never in the gap between the two, because that gap does not exist under the lock.
 */
@Component
@Primary
public class RunEventBroker implements RunEventAppender {

  private final RunEventAppender journalAppender;
  private final RunEventReader journalReader;
  private final RunEventHub hub;
  private final ConcurrentHashMap<String, Object> locksByRun = new ConcurrentHashMap<>();

  public RunEventBroker(
      RunEventAppender journalAppender, RunEventReader journalReader, RunnerProperties properties) {
    this.journalAppender = journalAppender;
    this.journalReader = journalReader;
    this.hub = new RunEventHub(properties.sseMaxSubscribers());
  }

  /**
   * Closes every active subscription and stops accepting new ones - see {@link
   * RunEventHub#shutdown()}. Runs before the application context is destroyed so an SSE
   * controller's active connections are completed cleanly rather than cut off by the JVM exiting
   * mid-response.
   */
  @PreDestroy
  public void shutdown() {
    hub.shutdown();
  }

  @Override
  public RunnerEvent append(String runId, LongFunction<RunnerEvent> eventFactory) {
    synchronized (lockFor(runId)) {
      RunnerEvent event = journalAppender.append(runId, eventFactory);
      // Only ever enqueues into each subscriber's own mailbox (see RunEventHub) - never blocks on
      // slow client I/O, so holding the per-run lock here never stalls a concurrent
      // replayAndSubscribe call for longer than that enqueue takes.
      hub.publish(event);
      return event;
    }
  }

  /**
   * Atomically replays every event for {@code runId} after {@code afterSequence} into {@code
   * subscriber}, then registers it for live events - all under the same per-run lock {@link
   * #append} uses, so no event can ever land in the gap between "read the replay snapshot" and
   * "start receiving live ones". Pass {@code afterSequence == 0} for the full history.
   *
   * <p>{@code afterSequence} is validated against the journal's own current high-water mark, taken
   * under this same lock: a value greater than that is a client claiming to have already seen an
   * event this run never produced (a stale/wrong runId, or a bug), and resuming from it anyway
   * would silently skip whatever the client actually never saw. When {@code afterSequence} already
   * equals that high-water mark <em>and</em> the run is terminal (its last event is {@code
   * RUN_FINISHED}), there is nothing left to replay and nothing more will ever be appended - the
   * subscription is registered and then immediately closed, rather than left open to sit idle until
   * a client disconnect or the emitter's own timeout notices what this call already knows.
   *
   * @throws InvalidEventResumeSequenceException if {@code afterSequence} is greater than the
   *     journal's current high-water mark for {@code runId}.
   * @throws dev.vlaisanem.automation.runner.service.exception.RunEventSubscriptionRejectedException
   *     if the hub is already at its configured subscriber capacity, or is shutting down - see
   *     {@link RunEventHub#subscribe}.
   */
  public RunEventSubscription replayAndSubscribe(
      String runId, long afterSequence, RunEventSubscriber subscriber) {
    synchronized (lockFor(runId)) {
      Optional<RunnerEvent> latest = journalReader.latest(runId);
      long latestSequence = latest.map(RunnerEvent::sequence).orElse(0L);
      if (afterSequence > latestSequence) {
        throw new InvalidEventResumeSequenceException(runId, afterSequence, latestSequence);
      }
      List<RunnerEvent> replay = journalReader.readAfter(runId, afterSequence);
      RunEventSubscription subscription = hub.subscribe(runId, replay, subscriber);
      boolean runAlreadyTerminal =
          latest.map(event -> event.type() == EventType.RUN_FINISHED).orElse(false);
      if (replay.isEmpty() && runAlreadyTerminal) {
        subscription.close();
      }
      return subscription;
    }
  }

  private Object lockFor(String runId) {
    return locksByRun.computeIfAbsent(runId, ignored -> new Object());
  }
}
