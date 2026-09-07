package dev.vlaisanem.automation.runner.service.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunOutcome;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.artifacts.ArtifactIngestionOutcome;
import dev.vlaisanem.automation.runner.service.artifacts.ArtifactIngestionService;
import dev.vlaisanem.automation.runner.service.artifacts.FakeArtifactRepository;
import dev.vlaisanem.automation.runner.service.config.RateLimitRule;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.exception.InvalidEventResumeSequenceException;
import dev.vlaisanem.automation.runner.service.repository.CommittedRunChange;
import dev.vlaisanem.automation.runner.service.repository.FakeRunLifecycleStore;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * D2.3 cutover: rewritten against {@link FakeRunLifecycleStore} instead of the retired {@code
 * FileBackedRunEventJournal}. A real Postgres-backed run row is now a hard prerequisite for any
 * event (the {@code fk_run_events_run} foreign key the real schema enforces has no equivalent in
 * the old file journal, which could append to any {@code runId} with no prior record at all) -
 * every test here calls {@link RunEventBroker#queue} first for exactly that reason. The 300-event
 * and 260-event stress tests use {@link RunEventBroker#append} with {@code TEST_STARTED} events
 * (the append-only path, unbounded while a run stays non-terminal) rather than repeatedly "queuing"
 * the same run, which a real run can only do once.
 */
class RunEventBrokerTest {

  private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

  @Test
  void appendPublishesToAnAlreadyRegisteredLiveSubscriber() throws Exception {
    RunEventBroker broker = newBroker();
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();
    broker.replayAndSubscribe("run-1", 0, subscriber);

    queue(broker, "run-1");
    startRunning(broker, "run-1");

    awaitReceivedCount(subscriber, 2);
    assertThat(subscriber.received)
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.RUN_QUEUED, EventType.RUN_STARTED);
  }

  @Test
  void replayAndSubscribeDeliversExistingHistoryThenLiveEvents() throws Exception {
    RunEventBroker broker = newBroker();
    queue(broker, "run-1");
    startRunning(broker, "run-1");

    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();
    broker.replayAndSubscribe("run-1", 0, subscriber);
    finish(broker, "run-1", RunStatus.SUCCEEDED, RunOutcome.SUCCEEDED);

    awaitReceivedCount(subscriber, 3);
    assertThat(subscriber.received).extracting(RunnerEvent::sequence).containsExactly(1L, 2L, 3L);
  }

  @Test
  void replayAfterASequenceOnlyReplaysNewerEvents() throws Exception {
    RunEventBroker broker = newBroker();
    queue(broker, "run-1");
    startRunning(broker, "run-1");

    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();
    broker.replayAndSubscribe("run-1", 1, subscriber); // already saw sequence 1

    awaitReceivedCount(subscriber, 1);
    assertThat(subscriber.received).extracting(RunnerEvent::sequence).containsExactly(2L);
  }

  /**
   * Stress-test companion to {@link
   * #replayAndSubscribeBlocksAConcurrentAppendUntilTheSubscriberIsRegistered} - that test proves
   * the atomicity guarantee deterministically; this one hammers the same guarantee under real
   * concurrent load (300 racing appends) to catch anything a single deterministic interleaving
   * could miss. A subscriber that starts via {@code replayAndSubscribe} while {@code append} calls
   * are continuously racing on another thread must still receive every event exactly once, in
   * order, with no gap and no duplicate.
   */
  @Test
  void replayAndSubscribeNeverMissesOrDuplicatesAnEventRacingConcurrently() throws Exception {
    RunEventBroker broker = newBroker();
    queue(broker, "run-1");
    startRunning(broker, "run-1"); // now RUNNING - stays non-terminal for the whole stress run
    int totalTestEvents = 300;
    ExecutorService publisher = Executors.newSingleThreadExecutor();

    Future<?> publishing =
        publisher.submit(
            () -> {
              for (int i = 0; i < totalTestEvents; i++) {
                appendTestEvent(broker, "run-1");
              }
            });

    // Let a handful of appends land first, so there is genuine history to replay, then subscribe
    // while publishing continues concurrently on the other thread.
    Thread.sleep(10);
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();
    broker.replayAndSubscribe("run-1", 0, subscriber);

    publishing.get(30, TimeUnit.SECONDS);
    publisher.shutdown();
    int totalEvents = totalTestEvents + 2; // + RUN_QUEUED + RUN_STARTED seeded above
    awaitReceivedCount(subscriber, totalEvents);

    assertThat(subscriber.received)
        .extracting(RunnerEvent::sequence)
        .containsExactlyElementsOf(LongStream.rangeClosed(1, totalEvents).boxed().toList());
  }

  /**
   * Regression test for the review's finding: a slow-consumer disconnect is detected inside {@link
   * RunEventHub.Subscription#offerLive}, on whatever thread calls {@link RunEventBroker#append} -
   * here, this test's own thread. If the subscriber's {@code onError} callback ran synchronously on
   * that path and threw, the exception would propagate out of {@code append} even though the event
   * was already durably written to the store. Proving {@code append} never throws here, and that
   * every appended event is still readable afterward, is what proves the callback is fully
   * decoupled from the publisher.
   */
  @Test
  void aSubscriberErrorCallbackThatThrowsNeverFailsAppendOrCorruptsTheStore() throws Exception {
    FakeRunLifecycleStore store = new FakeRunLifecycleStore();
    RunEventBroker broker = newBroker(store);
    queue(broker, "run-1");
    startRunning(broker, "run-1");
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();
    subscriber.blockOnEvent = true; // never drains, so the live mailbox eventually overflows
    subscriber.throwOnErrorCallback = true;
    broker.replayAndSubscribe("run-1", 0, subscriber);

    for (int i = 0; i < 260; i++) { // comfortably exceeds the live capacity (256)
      int index = i;
      assertThatCode(() -> appendTestEvent(broker, "run-1", index)).doesNotThrowAnyException();
    }

    assertThat(subscriber.errorLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(subscriber.error).hasMessageContaining("mailbox full");
    assertThat(store.readEventsAfter("run-1", 0)).hasSize(262); // RUN_QUEUED + RUN_STARTED + 260
  }

  /**
   * Deterministic replacement for a timing-based race test: a custom {@link RunLifecycleStore}
   * blocks mid-read, while the broker's per-run lock is held, so this test can prove - not just
   * hope - that a concurrent {@link RunEventBroker#append} cannot complete until the store read is
   * released and {@code replayAndSubscribe} has registered the subscriber. Once released, the
   * subscriber must see exactly the pre-existing replay event followed by the one concurrent live
   * append, with no gap and no duplicate.
   */
  @Test
  void replayAndSubscribeBlocksAConcurrentAppendUntilTheSubscriberIsRegistered() throws Exception {
    FakeRunLifecycleStore store = new FakeRunLifecycleStore();
    store.queue(
        "run-1",
        Environment.PUBLIC,
        Suite.SMOKE,
        NOW,
        List.of(),
        seq -> RunnerEvent.runQueued("run-1", seq, NOW)); // pre-existing
    BlockingLifecycleStore blockingStore = new BlockingLifecycleStore(store);
    RunEventBroker broker = newBroker(blockingStore);
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<RunEventSubscription> subscribing =
          executor.submit(() -> broker.replayAndSubscribe("run-1", 0, subscriber));
      assertThat(blockingStore.entered.await(5, TimeUnit.SECONDS)).isTrue();

      Future<RunnerEvent> appending = executor.submit(() -> appendTestEvent(broker, "run-1"));

      // The per-run lock is still held by the blocked replayAndSubscribe call - the concurrent
      // append must not be able to finish yet.
      assertThatThrownBy(() -> appending.get(300, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      blockingStore.release.countDown();

      subscribing.get(5, TimeUnit.SECONDS);
      appending.get(5, TimeUnit.SECONDS);

      awaitReceivedCount(subscriber, 2);
      assertThat(subscriber.received).extracting(RunnerEvent::sequence).containsExactly(1L, 2L);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Regression test for the review's finding: resuming from a sequence the store never produced (a
   * client's {@code Last-Event-ID} claiming to have seen something that does not exist) must be
   * rejected outright, not silently served as if it were {@code 0} or the latest.
   */
  @Test
  void replayAndSubscribeRejectsAnAfterSequenceAheadOfTheStore() {
    RunEventBroker broker = newBroker();
    queue(broker, "run-1");
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();

    assertThatThrownBy(() -> broker.replayAndSubscribe("run-1", 100, subscriber))
        .isInstanceOf(InvalidEventResumeSequenceException.class)
        .hasMessageContaining("100")
        .hasMessageContaining("1");
  }

  /** Same finding, for a runId the store has no record of at all - not just a stale one. */
  @Test
  void replayAndSubscribeRejectsAnAfterSequenceForAnUnknownRun() {
    RunEventBroker broker = newBroker();
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();

    assertThatThrownBy(() -> broker.replayAndSubscribe("never-seen", 1, subscriber))
        .isInstanceOf(InvalidEventResumeSequenceException.class);
  }

  /**
   * Regression test for the review's finding: a run the store has no record of at all still accepts
   * a resume point of {@code 0} (the "give me everything" sentinel) without being rejected as
   * "ahead of the store" - {@code 0} is never ahead of anything.
   */
  @Test
  void replayAndSubscribeWithZeroIsNeverRejectedEvenForAnUnknownRun() {
    RunEventBroker broker = newBroker();
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();

    assertThatCode(() -> broker.replayAndSubscribe("never-seen", 0, subscriber))
        .doesNotThrowAnyException();
  }

  /**
   * Regression test for the review's finding: a client reconnecting exactly at a terminal run's
   * last sequence has nothing left to replay and nothing more will ever be appended - the stream
   * must complete immediately rather than sit open until the emitter's own timeout.
   */
  @Test
  void replayAndSubscribeAtExactlyTheLatestTerminalSequenceCompletesImmediately() throws Exception {
    RunEventBroker broker = newBroker();
    queue(broker, "run-1");
    startRunning(broker, "run-1");
    finish(broker, "run-1", RunStatus.SUCCEEDED, RunOutcome.SUCCEEDED);
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();

    broker.replayAndSubscribe("run-1", 3, subscriber);

    assertThat(subscriber.completedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(subscriber.received).isEmpty();
  }

  /**
   * Contrast case: resuming exactly at the latest sequence of a run that is NOT yet terminal must
   * stay open - more events may still be appended, unlike the terminal case above.
   */
  @Test
  void replayAndSubscribeAtExactlyTheLatestNonTerminalSequenceStaysOpen() throws Exception {
    RunEventBroker broker = newBroker();
    queue(broker, "run-1");
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();

    broker.replayAndSubscribe("run-1", 1, subscriber);

    assertThat(subscriber.completedLatch.await(300, TimeUnit.MILLISECONDS)).isFalse();
  }

  /**
   * Definition-of-done item for the SSE layer: {@code @PreDestroy} must actually reach the hub and
   * close whatever subscriptions are still active, rather than the broker just discarding them
   * silently on shutdown.
   */
  @Test
  void shutdownClosesActiveSubscriptions() throws Exception {
    RunEventBroker broker = newBroker();
    queue(broker, "run-1");
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();
    broker.replayAndSubscribe("run-1", 0, subscriber);

    broker.shutdown();

    assertThat(subscriber.completedLatch.await(5, TimeUnit.SECONDS)).isTrue();
  }

  /**
   * [P1] fix - {@code append} used to call {@code hub.publish} before artifact ingestion, and only
   * after releasing the per-run lock: a client that invalidates its artifacts query the instant it
   * observes {@code TEST_FAILED}/{@code TEST_ABORTED} over SSE could win that race and see an empty
   * list, with no further chance to refresh before {@code RUN_FINISHED}. Proves the fix
   * deterministically: a blocking {@link ArtifactIngestionService} holds ingestion open, and while
   * it does, a subscriber already registered for live events must not have received the {@code
   * TEST_FAILED} event yet - only once ingestion is released does the event actually arrive.
   */
  @Test
  void appendDoesNotPublishATestFailedEventUntilArtifactIngestionCompletes() throws Exception {
    BlockingArtifactIngestionService blockingIngestion = new BlockingArtifactIngestionService();
    RunEventBroker broker =
        new RunEventBroker(new FakeRunLifecycleStore(), testProperties(), blockingIngestion);
    queue(broker, "run-1");
    startRunning(broker, "run-1");
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();
    broker.replayAndSubscribe("run-1", 0, subscriber);
    awaitReceivedCount(subscriber, 2); // RUN_QUEUED, RUN_STARTED already delivered

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<RunnerEvent> appending =
          executor.submit(
              () ->
                  broker.append(
                      "run-1",
                      seq ->
                          RunnerEvent.testFailed(
                              "run-1", seq, NOW, "test-1", "Some test", "boom")));
      assertThat(blockingIngestion.entered.await(5, TimeUnit.SECONDS)).isTrue();

      // Ingestion is still blocked - the subscriber, already registered for live events, must not
      // have received TEST_FAILED yet.
      Thread.sleep(200);
      assertThat(subscriber.received)
          .extracting(RunnerEvent::type)
          .containsExactly(EventType.RUN_QUEUED, EventType.RUN_STARTED);

      blockingIngestion.release.countDown();
      appending.get(5, TimeUnit.SECONDS);

      awaitReceivedCount(subscriber, 3);
      assertThat(subscriber.received)
          .extracting(RunnerEvent::type)
          .containsExactly(EventType.RUN_QUEUED, EventType.RUN_STARTED, EventType.TEST_FAILED);
    } finally {
      executor.shutdownNow();
    }
  }

  private RunEventBroker newBroker() {
    return newBroker(new FakeRunLifecycleStore());
  }

  private RunEventBroker newBroker(RunLifecycleStore store) {
    return new RunEventBroker(store, testProperties(), noopArtifactIngestionService());
  }

  /**
   * None of this test class's scenarios ever append a {@code TEST_FAILED}/{@code TEST_ABORTED}
   * event (only {@code TEST_STARTED}, via {@link #appendTestEvent}), so {@link RunEventBroker}'s
   * own D2.4 artifact-ingestion hook never actually fires here - a real {@link
   * ArtifactIngestionService} wired to an in-memory {@link FakeArtifactRepository} satisfies the
   * constructor without needing a real manifest file or database.
   */
  private ArtifactIngestionService noopArtifactIngestionService() {
    return new ArtifactIngestionService(
        new ObjectMapper(), new FakeArtifactRepository(), testProperties());
  }

  private void queue(RunEventBroker broker, String runId) {
    broker.queue(
        runId,
        Environment.PUBLIC,
        Suite.SMOKE,
        NOW,
        List.of(),
        seq -> RunnerEvent.runQueued(runId, seq, NOW));
  }

  private void startRunning(RunEventBroker broker, String runId) {
    // RunStateMachine only allows QUEUED -> STARTING -> RUNNING, never QUEUED -> RUNNING directly.
    broker.transitionIfNonTerminal(runId, run -> run.transitionTo(RunStatus.STARTING, NOW), null);
    broker.transitionIfNonTerminal(
        runId,
        run -> run.transitionTo(RunStatus.RUNNING, NOW),
        seq -> RunnerEvent.runStarted(runId, seq, NOW));
  }

  private void finish(RunEventBroker broker, String runId, RunStatus status, RunOutcome outcome) {
    broker.transitionIfNonTerminal(
        runId,
        run -> run.transitionTo(status, NOW, 0, null),
        seq -> RunnerEvent.runFinished(runId, seq, NOW, outcome, null));
  }

  private RunnerEvent appendTestEvent(RunEventBroker broker, String runId) {
    return appendTestEvent(broker, runId, 0);
  }

  private RunnerEvent appendTestEvent(RunEventBroker broker, String runId, int index) {
    return broker.append(
        runId,
        seq -> RunnerEvent.testStarted(runId, seq, NOW, "test-" + index, "Some test " + index));
  }

  /**
   * A minimal-but-valid properties object for constructing a broker directly - only {@code
   * sseMaxSubscribers()} is ever read from it.
   */
  private static RunnerProperties testProperties() {
    return new RunnerProperties(
        ".",
        Duration.ofSeconds(30),
        "raw",
        "logs",
        "src/test/resources/catalog/public-test-catalog.json",
        "artifacts",
        1024 * 1024,
        Duration.ofSeconds(5),
        Duration.ofSeconds(1),
        1,
        Duration.ofMillis(150),
        Duration.ofSeconds(5),
        10_000,
        Duration.ofSeconds(15),
        Duration.ofMinutes(10),
        new RateLimitRule(5, Duration.ofMinutes(1)),
        new RateLimitRule(10, Duration.ofMinutes(1)),
        new RateLimitRule(3, Duration.ofMinutes(1)),
        new RateLimitRule(10, Duration.ofHours(1)),
        new RateLimitRule(10, Duration.ofMinutes(1)),
        new RateLimitRule(120, Duration.ofMinutes(1)),
        new RateLimitRule(30, Duration.ofMinutes(1)),
        3,
        16384);
  }

  /**
   * Test double that blocks inside {@link #ingestAvailableEntries} until released, so a test can
   * deterministically prove {@link RunEventBroker#append}'s own ordering guarantee (ingest, then
   * publish, under the same lock) - not just usually working out under timing that happens to favor
   * it.
   */
  private static final class BlockingArtifactIngestionService extends ArtifactIngestionService {
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    BlockingArtifactIngestionService() {
      super(new ObjectMapper(), new FakeArtifactRepository(), testProperties());
    }

    @Override
    public ArtifactIngestionOutcome ingestAvailableEntries(String runId, boolean runTerminal) {
      entered.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return ArtifactIngestionOutcome.SUCCEEDED;
    }
  }

  /**
   * Test double that blocks inside {@code latestEvent} until released, so a test can
   * deterministically prove the broker's per-run lock is actually held for the whole "read replay
   * snapshot" step - not just usually working out under timing that happens to favor it. Blocks in
   * {@code latestEvent} specifically because {@link RunEventBroker#replayAndSubscribe} calls that
   * first, to validate the resume point against the current high-water mark before ever reading the
   * replay batch.
   */
  private static final class BlockingLifecycleStore implements RunLifecycleStore {
    private final RunLifecycleStore delegate;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    private BlockingLifecycleStore(RunLifecycleStore delegate) {
      this.delegate = delegate;
    }

    @Override
    public CommittedRunChange queue(
        String runId,
        Environment environment,
        Suite suite,
        Instant requestedAt,
        List<SelectedTestSnapshot> selectedTests,
        LongFunction<RunnerEvent> queuedEventFactory) {
      return delegate.queue(
          runId, environment, suite, requestedAt, selectedTests, queuedEventFactory);
    }

    @Override
    public Optional<CommittedRunChange> transitionIfNonTerminal(
        String runId, UnaryOperator<Run> transition, LongFunction<RunnerEvent> eventFactory) {
      return delegate.transitionIfNonTerminal(runId, transition, eventFactory);
    }

    @Override
    public Optional<RunnerEvent> appendEventIfNonTerminal(
        String runId, LongFunction<RunnerEvent> eventFactory) {
      return delegate.appendEventIfNonTerminal(runId, eventFactory);
    }

    @Override
    public Optional<Run> findById(String runId) {
      return delegate.findById(runId);
    }

    @Override
    public List<Run> findAll() {
      return delegate.findAll();
    }

    @Override
    public List<Run> findNonTerminal() {
      return delegate.findNonTerminal();
    }

    @Override
    public List<RunnerEvent> readEventsAfter(String runId, long afterSequence) {
      return delegate.readEventsAfter(runId, afterSequence);
    }

    @Override
    public Optional<RunnerEvent> latestEvent(String runId) {
      entered.countDown();
      try {
        release.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      return delegate.latestEvent(runId);
    }
  }

  private void awaitReceivedCount(RunEventHubTest.RecordingSubscriber subscriber, int expected)
      throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(10);
    while (Instant.now().isBefore(deadline)) {
      if (subscriber.received.size() >= expected) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Expected " + expected + " events, got " + subscriber.received.size());
  }
}
