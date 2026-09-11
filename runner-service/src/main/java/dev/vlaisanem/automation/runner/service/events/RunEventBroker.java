package dev.vlaisanem.automation.runner.service.events;

import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.artifacts.ArtifactIngestionService;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.exception.InvalidEventResumeSequenceException;
import dev.vlaisanem.automation.runner.service.metrics.RunnerMetrics;
import dev.vlaisanem.automation.runner.service.repository.CommittedRunChange;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import dev.vlaisanem.automation.runner.service.repository.RunLockStripes;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/**
 * The single entry point for both writing and subscribing to a run's canonical event timeline,
 * backed by {@link RunLifecycleStore}. Internally owns the store and a {@link RunEventHub} (live
 * fan-out) - callers never touch either directly.
 *
 * <p>{@link RunLifecycleStore}'s own row lock serializes concurrent database writers on the same
 * run but releases at each call's {@code COMMIT}, so it can't by itself close the gap between "the
 * transaction committed" and "the result was published to live subscribers". This class's per-run
 * lock (from a fixed-size {@link RunLockStripes}) is what closes that gap: every write method holds
 * it around "call the store, then publish to the hub", and {@link #replayAndSubscribe} holds it
 * around "read the replay snapshot, then register the subscriber" - so a live event is always
 * published either strictly before the replay snapshot (included in the replay) or strictly after
 * subscription (arrives live), never in between.
 *
 * <p>Exposes three write paths mirroring {@link RunLifecycleStore}'s split: {@link #queue} and
 * {@link #transitionIfNonTerminal} for lifecycle events (each returns the full {@link
 * CommittedRunChange}, since a caller may need the resulting {@link Run} snapshot too); {@link
 * #append}, implementing {@link RunEventAppender}, for {@code TEST_*}/{@code STEP_*} events.
 */
@Component
public class RunEventBroker implements RunEventAppender {

  private final RunLifecycleStore store;
  private final RunEventHub hub;
  private final ArtifactIngestionService artifactIngestionService;
  private final RunLockStripes lockStripes = new RunLockStripes();

  public RunEventBroker(
      RunLifecycleStore store,
      RunnerProperties properties,
      ArtifactIngestionService artifactIngestionService,
      RunnerMetrics metrics,
      MeterRegistry meterRegistry) {
    this.store = store;
    this.hub = new RunEventHub(properties.sseMaxSubscribers(), metrics, meterRegistry);
    this.artifactIngestionService = artifactIngestionService;
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

  public CommittedRunChange queue(
      String runId,
      Environment environment,
      Suite suite,
      Instant requestedAt,
      List<SelectedTestSnapshot> selectedTests,
      LongFunction<RunnerEvent> queuedEventFactory) {
    synchronized (lockFor(runId)) {
      CommittedRunChange change =
          store.queue(runId, environment, suite, requestedAt, selectedTests, queuedEventFactory);
      // Only enqueues into each subscriber's mailbox, never blocks on slow client I/O, so holding
      // the per-run lock here never stalls a concurrent replayAndSubscribe for long.
      hub.publish(change.event());
      return change;
    }
  }

  public Optional<CommittedRunChange> transitionIfNonTerminal(
      String runId, UnaryOperator<Run> transition, LongFunction<RunnerEvent> eventFactory) {
    synchronized (lockFor(runId)) {
      Optional<CommittedRunChange> result =
          store.transitionIfNonTerminal(runId, transition, eventFactory);
      result.ifPresent(
          change -> {
            if (change.event() != null) {
              hub.publish(change.event());
            }
          });
      return result;
    }
  }

  /**
   * {@code TEST_*}/{@code STEP_*} events only, via {@link
   * RunLifecycleStore#appendEventIfNonTerminal} - preserves {@link RunEventAppender}'s original
   * throwing contract even though the store returns an empty {@link Optional} for that case.
   *
   * <p>A {@code TEST_FAILED}/{@code TEST_ABORTED} event additionally triggers an incremental {@link
   * ArtifactIngestionService} pass, before {@code hub.publish} and still inside the per-run lock,
   * so a client that invalidates its artifacts query the instant it observes that event over SSE
   * can never race ahead of ingestion and see an empty list.
   */
  @Override
  public RunnerEvent append(String runId, LongFunction<RunnerEvent> eventFactory) {
    synchronized (lockFor(runId)) {
      RunnerEvent event =
          store
              .appendEventIfNonTerminal(runId, eventFactory)
              .orElseThrow(
                  () ->
                      new RunEventJournalConflictException(
                          "Run " + runId + " no longer accepts events"));
      if (event.type() == EventType.TEST_FAILED || event.type() == EventType.TEST_ABORTED) {
        artifactIngestionService.ingestAvailableEntries(runId, false);
      }
      hub.publish(event);
      return event;
    }
  }

  /**
   * Atomically replays every event for {@code runId} after {@code afterSequence} into {@code
   * subscriber}, then registers it for live events, all under the same per-run lock every write
   * method uses, so no event can land in the gap between the two. Pass {@code afterSequence == 0}
   * for the full history.
   *
   * <p>{@code afterSequence} is validated against the store's current high-water mark: a greater
   * value is a client claiming to have seen an event this run never produced, and resuming from it
   * would silently skip whatever it actually never saw. When {@code afterSequence} already equals
   * that mark and the run is terminal, the subscription is registered and immediately closed rather
   * than left open to sit idle.
   *
   * @throws InvalidEventResumeSequenceException if {@code afterSequence} is greater than the
   *     store's current high-water mark for {@code runId}.
   * @throws dev.vlaisanem.automation.runner.service.exception.RunEventSubscriptionRejectedException
   *     if the hub is already at its configured subscriber capacity, or is shutting down.
   */
  public RunEventSubscription replayAndSubscribe(
      String runId, long afterSequence, RunEventSubscriber subscriber) {
    synchronized (lockFor(runId)) {
      Optional<RunnerEvent> latest = store.latestEvent(runId);
      long latestSequence = latest.map(RunnerEvent::sequence).orElse(0L);
      if (afterSequence > latestSequence) {
        throw new InvalidEventResumeSequenceException(runId, afterSequence, latestSequence);
      }
      List<RunnerEvent> replay = store.readEventsAfter(runId, afterSequence);
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
    return lockStripes.lockFor(runId);
  }
}
