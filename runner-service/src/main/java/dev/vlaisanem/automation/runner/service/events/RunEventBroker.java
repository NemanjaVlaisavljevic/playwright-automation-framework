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
import dev.vlaisanem.automation.runner.service.repository.CommittedRunChange;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import dev.vlaisanem.automation.runner.service.repository.RunLockStripes;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/**
 * D2.3 (docs/DEPLOYMENT_ARCHITECTURE.md section 3) - the single entry point for both writing and
 * subscribing to a run's canonical event timeline, now backed by {@link RunLifecycleStore} (in
 * production, {@code JdbcRunStore} - a real Postgres transaction per write) instead of the retired
 * {@code FileBackedRunEventJournal}. Internally owns the store and a {@link RunEventHub} (live
 * fan-out) - callers never touch either directly.
 *
 * <p><strong>What changed at this cutover, and why the per-run lock below still matters just as
 * much as before</strong>: {@link RunLifecycleStore}'s own {@code SELECT ... FOR UPDATE} row lock
 * serializes concurrent *database writers* on the same run, but it is released at each call's own
 * {@code COMMIT} - it cannot by itself close the gap between "this store's transaction committed"
 * and "the result was published to live subscribers" the way a single in-process lock spanning both
 * steps can (see {@code JdbcRunStore}'s own Javadoc for the fuller explanation). This class's
 * {@code locksByRun} - unchanged from the pre-cutover design - is exactly that lock: every write
 * method takes it around "call the store, then publish the committed event to the hub", and {@link
 * #replayAndSubscribe} takes it around "read the replay snapshot from the store, then register the
 * subscriber". Serializing those against each other for the same run is what still guarantees a
 * subscriber's replay batch and the live events that follow it are gapless and duplicate-free - a
 * live event can only be published either strictly before the replay snapshot is taken (so it is
 * included in the replay) or strictly after the subscriber is registered (so it arrives live),
 * never in the gap between the two, because that gap does not exist under this lock. The lock
 * itself comes from a fixed-size {@link RunLockStripes}, not one entry per {@code runId} - see that
 * class's own Javadoc for why an unbounded per-run map is unsafe to prune and was replaced.
 *
 * <p>Exposes three distinct write paths, mirroring {@link RunLifecycleStore}'s own split (a review
 * finding from D2.2: a single generic "append" cannot express "also transition the run's status"):
 * {@link #queue} and {@link #transitionIfNonTerminal} for {@code RunLifecycleCoordinator}'s
 * lifecycle events (each returns the full {@link CommittedRunChange}, not just the event, since a
 * caller may need the resulting {@link Run} snapshot too); {@link #append}, still implementing
 * {@link RunEventAppender} unchanged, for {@code ListenerEventIngestorFactory}'s {@code TEST_*}/
 * {@code STEP_*} events - the one caller whose events genuinely fit that narrow, status-free
 * interface.
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
      ArtifactIngestionService artifactIngestionService) {
    this.store = store;
    this.hub = new RunEventHub(properties.sseMaxSubscribers());
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
      // Only ever enqueues into each subscriber's own mailbox (see RunEventHub) - never blocks on
      // slow client I/O, so holding the per-run lock here never stalls a concurrent
      // replayAndSubscribe call for longer than that enqueue takes.
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
   * throwing contract (a {@link RunEventJournalConflictException} once the run's timeline is
   * closed) even though the store itself returns an empty {@link Optional} for that case, so {@code
   * ListenerEventIngestor} needs no changes at all.
   *
   * <p>D2.4 - a {@code TEST_FAILED}/{@code TEST_ABORTED} event additionally triggers an incremental
   * {@link ArtifactIngestionService} pass for this run, <em>before</em> {@code hub.publish}, under
   * the same per-run lock (a review finding, correcting an earlier version of this method that ran
   * ingestion after publishing and after releasing the lock): a client that invalidates its
   * artifacts query the instant it observes {@code TEST_FAILED}/{@code TEST_ABORTED} over SSE must
   * never be able to win that race and see an empty list, with no further chance to refresh before
   * {@code RUN_FINISHED}. Running ingestion first, still inside the lock, guarantees the artifact
   * metadata is already durably ingested by the time any subscriber can possibly observe this event
   * at all - see {@link ArtifactIngestionService}'s own Javadoc for why this call can never itself
   * fail this method regardless.
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
   * subscriber}, then registers it for live events - all under the same per-run lock every write
   * method uses, so no event can ever land in the gap between "read the replay snapshot" and "start
   * receiving live ones". Pass {@code afterSequence == 0} for the full history.
   *
   * <p>{@code afterSequence} is validated against the store's own current high-water mark, taken
   * under this same lock: a value greater than that is a client claiming to have already seen an
   * event this run never produced (a stale/wrong runId, or a bug), and resuming from it anyway
   * would silently skip whatever the client actually never saw. When {@code afterSequence} already
   * equals that high-water mark <em>and</em> the run is terminal (its last event is {@code
   * RUN_FINISHED}), there is nothing left to replay and nothing more will ever be appended - the
   * subscription is registered and then immediately closed, rather than left open to sit idle until
   * a client disconnect or the emitter's own timeout notices what this call already knows.
   *
   * @throws InvalidEventResumeSequenceException if {@code afterSequence} is greater than the
   *     store's current high-water mark for {@code runId}.
   * @throws dev.vlaisanem.automation.runner.service.exception.RunEventSubscriptionRejectedException
   *     if the hub is already at its configured subscriber capacity, or is shutting down - see
   *     {@link RunEventHub#subscribe}.
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
