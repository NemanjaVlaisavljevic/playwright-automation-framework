package dev.vlaisanem.automation.runner.service.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunOutcome;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.artifacts.ArtifactIngestionService;
import dev.vlaisanem.automation.runner.service.artifacts.FakeArtifactRepository;
import dev.vlaisanem.automation.runner.service.config.RateLimitRule;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.repository.CommittedRunChange;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import dev.vlaisanem.automation.runner.service.repository.jdbc.JdbcRunStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;
import java.util.stream.LongStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * D2.3 - the acceptance matrix the live-wiring gap explicitly called for, run against a real {@link
 * RunEventBroker} wrapping the real {@link JdbcRunStore} (not {@code FakeRunLifecycleStore}) and a
 * real Testcontainers Postgres: concurrent appends under real subscriber traffic, a
 * replay/subscribe race with no gap or duplicate, and crash-between-events recovery via
 * reconnect-replay. Each mirrors an existing {@code FakeRunLifecycleStore}-backed test in {@code
 * RunEventBrokerTest} - proving the exact same guarantee holds against the real store and a real
 * database, not only against the fake that stands in for it everywhere else.
 */
@Testcontainers
class RunEventBrokerJdbcAcceptanceTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine");

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  private static JdbcRunStore store;

  @BeforeAll
  static void migrateAndBuildStore() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();

    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());

    ObjectMapper objectMapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    TransactionTemplate transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    store = new JdbcRunStore(jdbcTemplate, transactionTemplate, objectMapper);
  }

  /**
   * Mirrors {@code
   * RunEventBrokerTest#replayAndSubscribeNeverMissesOrDuplicatesAnEventRacingConcurrently} against
   * the real store: a subscriber that registers via {@code replayAndSubscribe} while a separate
   * thread continuously appends {@code TEST_STARTED} events must receive every one exactly once, in
   * order, with no gap and no duplicate - then cross-checked against what {@link
   * JdbcRunStore#readEventsAfter} itself reads back from Postgres, proving live delivery and
   * durable storage agree completely, not just that the subscriber "received a plausible number" of
   * events.
   *
   * <p>[P2] fix - a plain {@code Thread.sleep(10)} before subscribing does not guarantee the
   * publisher is still mid-flight when {@code replayAndSubscribe} registers: on a slow/loaded CI
   * runner all 200 appends could already be done, and the test would then pass purely through the
   * replay path even though its name claims to exercise live delivery. Fixed with two latches: the
   * publisher writes a fixed prefix, signals it has, then blocks until released - the test only
   * subscribes (and only then releases the rest) once that prefix is provably already written,
   * guaranteeing the remaining writes are genuinely concurrent with, not merely coincidentally
   * overlapping, the subscription.
   */
  @Test
  void concurrentAppendsWithALiveSubscriberDeliverEveryEventExactlyOnceInOrder() throws Exception {
    RunEventBroker broker = newBroker();
    String runId = newRunId();
    queue(broker, runId);
    startRunning(broker, runId); // RUNNING - stays non-terminal for the whole stress run

    int prefixEvents = 20;
    int totalTestEvents = 200;
    CountDownLatch prefixWritten = new CountDownLatch(1);
    CountDownLatch releaseRest = new CountDownLatch(1);
    ExecutorService publisher = Executors.newSingleThreadExecutor();
    try {
      Future<?> publishing =
          publisher.submit(
              () -> {
                for (int i = 0; i < prefixEvents; i++) {
                  appendTestEvent(broker, runId, i);
                }
                prefixWritten.countDown();
                try {
                  releaseRest.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  throw new RuntimeException(e);
                }
                for (int i = prefixEvents; i < totalTestEvents; i++) {
                  appendTestEvent(broker, runId, i);
                }
              });

      assertThat(prefixWritten.await(10, TimeUnit.SECONDS))
          .as("the prefix must be durably written before the subscriber ever registers")
          .isTrue();
      RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();
      broker.replayAndSubscribe(runId, 0, subscriber);
      releaseRest.countDown();

      publishing.get(60, TimeUnit.SECONDS);
      int totalEvents = totalTestEvents + 2; // + RUN_QUEUED + RUN_STARTED seeded above
      awaitReceivedCount(subscriber, totalEvents);

      assertThat(subscriber.received)
          .extracting(RunnerEvent::sequence)
          .containsExactlyElementsOf(LongStream.rangeClosed(1, totalEvents).boxed().toList());

      List<RunnerEvent> persisted = store.readEventsAfter(runId, 0);
      assertThat(persisted)
          .as("live delivery must match durable Postgres storage exactly")
          .extracting(RunnerEvent::sequence)
          .containsExactlyElementsOf(
              subscriber.received.stream().map(RunnerEvent::sequence).toList());
    } finally {
      publisher.shutdownNow();
    }
  }

  /**
   * The deterministic companion to the stress test above: a concurrent {@code append} is held open
   * (via {@link BlockingReplayStore}) until a {@code replayAndSubscribe} call has provably entered
   * and is blocked waiting for the same per-run lock, proving the two really do serialize against
   * each other on the real store's lock path too - not just under timing that happens to favor it.
   */
  @Test
  void replayAndSubscribeBlocksAConcurrentAppendUntilTheSubscriberIsRegistered() throws Exception {
    BlockingReplayStore blockingStore = new BlockingReplayStore(store);
    RunEventBroker broker = newBroker(blockingStore);
    String runId = newRunId();
    queue(broker, runId);
    startRunning(broker, runId);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();
      Future<?> replaying = executor.submit(() -> broker.replayAndSubscribe(runId, 0, subscriber));
      assertThat(blockingStore.entered.await(5, TimeUnit.SECONDS))
          .as("replayAndSubscribe must have entered latestEvent() by now")
          .isTrue();

      Future<RunnerEvent> appending =
          executor.submit(() -> broker.append(runId, seq -> testStartedEvent(runId, seq, 0)));
      assertStillBlockedAfter(appending, Duration.ofMillis(300));

      blockingStore.release.countDown();
      replaying.get(5, TimeUnit.SECONDS);
      RunnerEvent appended = appending.get(5, TimeUnit.SECONDS);

      assertThat(appended.sequence()).isEqualTo(3L);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * The "cold start" crash-recovery variant: a run's complete lifecycle (queue, start, several test
   * events, finish) commits with <em>no subscriber ever attached</em> - simulating a subscriber
   * process that crashed, or simply never connected, for the run's entire execution. A brand-new
   * {@link RunEventBroker} instance (its own fresh {@code RunEventHub}, with no in-memory state
   * carried over - modeling a real process restart) wrapping the very same {@link JdbcRunStore}
   * must still recover the run's complete history via {@code replayAndSubscribe} alone, purely from
   * what Postgres persisted, and see it as already-terminal (the subscription closes itself
   * immediately after replaying the trailing {@code RUN_FINISHED}, per {@link
   * RunEventBroker#replayAndSubscribe}'s own contract). See {@link
   * #aCrashBetweenCommitAndPublishIsFullyRecoveredViaReconnectReplay} for the "warm" variant this
   * one does not cover: a subscriber that already saw events 1..N, then a genuine gap at N+1.
   */
  @Test
  void aRunsCompleteHistorySurvivesWithNoLiveSubscriberAndIsFullyRecoveredViaReplay()
      throws Exception {
    RunEventBroker crashedBroker = newBroker();
    String runId = newRunId();
    queue(crashedBroker, runId);
    startRunning(crashedBroker, runId);
    for (int i = 0; i < 5; i++) {
      appendTestEvent(crashedBroker, runId, i);
    }
    finish(crashedBroker, runId, RunStatus.SUCCEEDED, RunOutcome.SUCCEEDED);

    RunEventBroker recoveredBroker = newBroker();
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();
    recoveredBroker.replayAndSubscribe(runId, 0, subscriber);

    assertThat(subscriber.completedLatch.await(5, TimeUnit.SECONDS))
        .as("an already-terminal run's replay-only subscription must close itself immediately")
        .isTrue();
    assertThat(subscriber.received)
        .extracting(RunnerEvent::type)
        .containsExactly(
            EventType.RUN_QUEUED,
            EventType.RUN_STARTED,
            EventType.TEST_STARTED,
            EventType.TEST_STARTED,
            EventType.TEST_STARTED,
            EventType.TEST_STARTED,
            EventType.TEST_STARTED,
            EventType.RUN_FINISHED);
    assertThat(subscriber.received)
        .extracting(RunnerEvent::sequence)
        .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
  }

  /**
   * [P1] fix - the actual crash window the D2 design calls for, which {@link
   * #aRunsCompleteHistorySurvivesWithNoLiveSubscriberAndIsFullyRecoveredViaReplay} does not
   * simulate: a subscriber has already seen events {@code 1..N}, sequence {@code N+1} then commits
   * to Postgres, and the process dies <em>between that commit and the broker's own {@code
   * hub.publish}</em> - the original subscriber never receives {@code N+1} at all. Modeled here by
   * committing {@code N+1} directly through the real {@link JdbcRunStore}, bypassing {@code
   * RunEventBroker} entirely (the broker is what would have called {@code hub.publish} - skipping
   * it is exactly what "the process died right there" means). A brand-new {@link RunEventBroker}
   * instance (a fresh in-memory {@code RunEventHub} - modeling the actual process restart) then
   * reconnects with {@code afterSequence = N} and must recover <em>exactly</em> {@code N+1} - not a
   * duplicate of anything the original subscriber already saw, not a gap - and, since {@code N+1}
   * here is the run's own {@code RUN_FINISHED}, close the subscription immediately.
   */
  @Test
  void aCrashBetweenCommitAndPublishIsFullyRecoveredViaReconnectReplay() throws Exception {
    RunEventBroker firstBroker = newBroker();
    String runId = newRunId();
    queue(firstBroker, runId); // sequence 1: RUN_QUEUED
    startRunning(firstBroker, runId); // sequence 2: RUN_STARTED (STARTING itself carries no event)

    RunEventHubTest.RecordingSubscriber originalSubscriber =
        new RunEventHubTest.RecordingSubscriber();
    firstBroker.replayAndSubscribe(runId, 0, originalSubscriber);
    awaitReceivedCount(originalSubscriber, 2);
    assertThat(originalSubscriber.received)
        .extracting(RunnerEvent::sequence)
        .containsExactly(1L, 2L);

    // The simulated crash: sequence 3 (RUN_FINISHED) commits directly through the real store,
    // never going through firstBroker.transitionIfNonTerminal - so hub.publish is never called for
    // it. This is deliberately not "a slow subscriber" or "a broker bug"; it is what actually
    // modeling "the process died right after the commit" looks like from the store's perspective.
    Instant finishedAt = NOW.plusSeconds(3);
    store.transitionIfNonTerminal(
        runId,
        run -> run.transitionTo(RunStatus.SUCCEEDED, finishedAt, 0, null),
        seq -> RunnerEvent.runFinished(runId, seq, finishedAt, RunOutcome.SUCCEEDED, null));

    // Proves the "crash" half actually happened, not just asserted: the original subscriber - still
    // live, still subscribed - must never have received sequence 3, since nothing ever published
    // it.
    Thread.sleep(50);
    assertThat(originalSubscriber.received)
        .as("the original subscriber must never see the event nothing ever published to it")
        .extracting(RunnerEvent::sequence)
        .containsExactly(1L, 2L);

    RunEventBroker recoveredBroker = newBroker();
    RunEventHubTest.RecordingSubscriber reconnectedSubscriber =
        new RunEventHubTest.RecordingSubscriber();
    recoveredBroker.replayAndSubscribe(runId, 2, reconnectedSubscriber);

    assertThat(reconnectedSubscriber.completedLatch.await(5, TimeUnit.SECONDS))
        .as("RUN_FINISHED must close a replay-only subscription immediately")
        .isTrue();
    assertThat(reconnectedSubscriber.received)
        .as("recovery must deliver exactly the missed event - no duplicate, no gap")
        .extracting(RunnerEvent::sequence)
        .containsExactly(3L);
    assertThat(reconnectedSubscriber.received)
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.RUN_FINISHED);
  }

  private static void assertStillBlockedAfter(Future<?> future, Duration timeout) {
    try {
      future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      throw new AssertionError(
          "expected the concurrent append to still be blocked, but it completed");
    } catch (TimeoutException expected) {
      // Still blocked, as required - the per-run lock is held by the in-flight replayAndSubscribe.
    } catch (Exception unexpected) {
      throw new AssertionError(
          "unexpected failure while waiting on the blocked append", unexpected);
    }
  }

  private RunEventBroker newBroker() {
    return newBroker(store);
  }

  private RunEventBroker newBroker(RunLifecycleStore delegateStore) {
    return new RunEventBroker(delegateStore, testProperties(), noopArtifactIngestionService());
  }

  /**
   * None of this class's acceptance scenarios ever append a {@code TEST_FAILED}/{@code
   * TEST_ABORTED} event (only {@code TEST_STARTED}, via {@link #appendTestEvent}), so {@link
   * RunEventBroker}'s own D2.4 artifact-ingestion hook never actually fires here - a real {@link
   * ArtifactIngestionService} wired to an in-memory {@link FakeArtifactRepository} satisfies the
   * constructor without needing a real manifest file.
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

  private RunnerEvent appendTestEvent(RunEventBroker broker, String runId, int index) {
    return broker.append(runId, seq -> testStartedEvent(runId, seq, index));
  }

  private static RunnerEvent testStartedEvent(String runId, long sequence, int index) {
    return RunnerEvent.testStarted(runId, sequence, NOW, "test-" + index, "Some test " + index);
  }

  private void awaitReceivedCount(RunEventHubTest.RecordingSubscriber subscriber, int expected)
      throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(30);
    while (Instant.now().isBefore(deadline)) {
      if (subscriber.received.size() >= expected) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Expected " + expected + " events, got " + subscriber.received.size());
  }

  private static String newRunId() {
    return "run-" + UUID.randomUUID();
  }

  /** A minimal-but-valid properties object - only {@code sseMaxSubscribers()} is ever read. */
  private RunnerProperties testProperties() {
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
        16384,
        Duration.ofDays(30),
        500,
        Duration.ofDays(14),
        Duration.ofHours(1),
        new RateLimitRule(10, Duration.ofHours(1)));
  }

  /**
   * Wraps the real {@link JdbcRunStore}, blocking inside {@code latestEvent} - the first call
   * {@link RunEventBroker#replayAndSubscribe} makes, to validate the resume point against the
   * store's current high-water mark - until released, so a test can deterministically prove the
   * per-run lock is genuinely held for the whole "read replay snapshot" step against the real
   * store's own lock path, not just usually working out under favorable timing.
   */
  private static final class BlockingReplayStore implements RunLifecycleStore {
    private final RunLifecycleStore delegate;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    private BlockingReplayStore(RunLifecycleStore delegate) {
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
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
      return delegate.latestEvent(runId);
    }

    @Override
    public List<String> findEligibleForCleanup(Instant now, Duration maxAge, int maxCount) {
      return delegate.findEligibleForCleanup(now, maxAge, maxCount);
    }

    @Override
    public List<String> findPendingCleanup() {
      return delegate.findPendingCleanup();
    }

    @Override
    public boolean claimForCleanup(String runId) {
      return delegate.claimForCleanup(runId);
    }

    @Override
    public void deleteRun(String runId) {
      delegate.deleteRun(runId);
    }

    @Override
    public List<String> findEligibleForArtifactPurge(Instant now, Duration maxAge) {
      return delegate.findEligibleForArtifactPurge(now, maxAge);
    }

    @Override
    public List<String> findPendingArtifactPurge() {
      return delegate.findPendingArtifactPurge();
    }

    @Override
    public boolean claimForArtifactPurge(String runId) {
      return delegate.claimForArtifactPurge(runId);
    }
  }
}
