package dev.vlaisanem.automation.runner.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.events.RunEventBroker;
import dev.vlaisanem.automation.runner.service.repository.FailingRunLifecycleStore;
import dev.vlaisanem.automation.runner.service.repository.FakeRunLifecycleStore;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * D2.3 cutover: rewritten against {@link FakeRunLifecycleStore} (behaviorally faithful to {@code
 * JdbcRunStore}, proven separately against a real Postgres in {@code databaseIntegrationTest})
 * instead of the retired in-memory {@code RunRepository}/file-backed {@code RunEventAppender} pair.
 * The old "emergency ERROR" tests (a store write succeeding but the separate journal append then
 * failing) no longer apply - one atomic store transaction means a failure anywhere rolls back
 * everything, so this class no longer has any emergency fallback of its own to test; the equivalent
 * coverage here is simpler: a failing store write leaves the run completely unchanged, and the
 * exception propagates unmodified for {@code RunService} to handle.
 */
class RunLifecycleCoordinatorTest {

  private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

  private final FakeRunLifecycleStore store = new FakeRunLifecycleStore();
  private final RunEventBroker broker = newBroker(store);
  private final RunLifecycleCoordinator coordinator =
      new RunLifecycleCoordinator(broker, noopArtifactIngestionService());

  @Test
  void queueSavesTheRunAndEmitsRunQueuedFirst() {
    Run run = coordinator.queue("run-1", Environment.PUBLIC, Suite.SMOKE, NOW);

    assertThat(run.status()).isEqualTo(RunStatus.QUEUED);
    List<RunnerEvent> recorded = store.readEventsAfter("run-1", 0);
    assertThat(recorded).hasSize(1);
    assertThat(recorded.getFirst().type()).isEqualTo(EventType.RUN_QUEUED);
    assertThat(recorded.getFirst().sequence()).isEqualTo(1L);
  }

  @Test
  void aFailingQueueWriteLeavesNoRunBehind() {
    RunLifecycleStore failingStore = new FailingRunLifecycleStore(store, EventType.RUN_QUEUED);
    RunLifecycleCoordinator failingCoordinator =
        new RunLifecycleCoordinator(newBroker(failingStore), noopArtifactIngestionService());

    assertThatThrownBy(
            () -> failingCoordinator.queue("run-1", Environment.PUBLIC, Suite.SMOKE, NOW))
        .isInstanceOf(UncheckedIOException.class);

    assertThat(store.findById("run-1")).isEmpty();

    assertThatThrownBy(() -> failingCoordinator.queue("run-2", Environment.PUBLIC, Suite.API, NOW))
        .isInstanceOf(UncheckedIOException.class);
    assertThat(store.findById("run-2")).isEmpty();
  }

  @Test
  void markStartingAppliesTheTransitionWithoutEmittingAnyEvent() {
    coordinator.queue("run-1", Environment.PUBLIC, Suite.SMOKE, NOW);

    boolean applied = coordinator.markStarting("run-1", NOW);

    assertThat(applied).isTrue();
    assertThat(store.findById("run-1").orElseThrow().status()).isEqualTo(RunStatus.STARTING);
    assertThat(store.readEventsAfter("run-1", 0))
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.RUN_QUEUED);
  }

  @Test
  void markRunningAppliesTheTransitionAndEmitsRunStarted() {
    coordinator.queue("run-1", Environment.PUBLIC, Suite.SMOKE, NOW);
    coordinator.markStarting("run-1", NOW);

    boolean applied = coordinator.markRunning("run-1", NOW);

    assertThat(applied).isTrue();
    assertThat(store.readEventsAfter("run-1", 0))
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.RUN_QUEUED, EventType.RUN_STARTED);
  }

  /**
   * Replaces the pre-cutover "emergency ERROR" test for this same failure point: with one atomic
   * store transaction, a failed write cannot leave a status change un-backed by its event any more
   * - it leaves the run completely unchanged instead, and the failure propagates for {@code
   * RunService}'s own existing top-level fallback to handle (unchanged by this cutover).
   */
  @Test
  void aFailingRunStartedWriteLeavesTheRunInStartingUnchanged() {
    RunLifecycleStore failingStore = new FailingRunLifecycleStore(store, EventType.RUN_STARTED);
    RunLifecycleCoordinator failingCoordinator =
        new RunLifecycleCoordinator(newBroker(failingStore), noopArtifactIngestionService());
    failingCoordinator.queue("run-1", Environment.PUBLIC, Suite.SMOKE, NOW);
    failingCoordinator.markStarting("run-1", NOW);

    assertThatThrownBy(() -> failingCoordinator.markRunning("run-1", NOW))
        .isInstanceOf(UncheckedIOException.class);

    Run unchanged = store.findById("run-1").orElseThrow();
    assertThat(unchanged.status()).isEqualTo(RunStatus.STARTING);
    assertThat(store.readEventsAfter("run-1", 0))
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.RUN_QUEUED);
  }

  /**
   * Regression test for the review's finding: a run whose lifecycle transition is lost to a
   * concurrent finalization must never get a RUN_STARTED it cannot honestly back up.
   */
  @Test
  void markRunningEmitsNothingWhenTheTransitionIsLostToAConcurrentFinish() {
    coordinator.queue("run-1", Environment.PUBLIC, Suite.SMOKE, NOW);
    coordinator.markStarting("run-1", NOW);
    coordinator.finishIfLive("run-1", RunStatus.CANCELLED, null, "cancelled first", NOW);

    boolean applied = coordinator.markRunning("run-1", NOW);

    assertThat(applied).isFalse();
    assertThat(store.readEventsAfter("run-1", 0))
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.RUN_QUEUED, EventType.RUN_FINISHED);
  }

  @Test
  void finishIfLiveMapsEveryTerminalStatusToItsOutcome() {
    for (RunStatus status :
        List.of(
            RunStatus.SUCCEEDED,
            RunStatus.FAILED,
            RunStatus.CANCELLED,
            RunStatus.TIMED_OUT,
            RunStatus.ERROR)) {
      String runId = "run-" + status;
      coordinator.queue(runId, Environment.PUBLIC, Suite.SMOKE, NOW);
      coordinator.markStarting(runId, NOW);
      coordinator.markRunning(runId, NOW);

      boolean applied = coordinator.finishIfLive(runId, status, 3, "detail-" + status, NOW);

      assertThat(applied).isTrue();
      RunnerEvent finished = lastEvent(runId);
      assertThat(finished.type()).isEqualTo(EventType.RUN_FINISHED);
      assertThat(finished.runOutcome()).isEqualTo(expectedOutcome(status));
      assertThat(finished.detail()).isEqualTo("detail-" + status);
    }
  }

  /**
   * Regression test for the review's finding: {@code RUN_QUEUED} followed directly by {@code
   * RUN_FINISHED(CANCELLED)} - a run cancelled before ever reaching STARTING/RUNNING - must never
   * carry a RUN_STARTED or any TEST_* event, and its canonical sequence must stay continuous.
   */
  @Test
  void queuedThenCancelledEmitsOnlyQueuedThenFinishedWithNoStartedInBetween() {
    coordinator.queue("run-1", Environment.PUBLIC, Suite.SMOKE, NOW);

    boolean applied =
        coordinator.finishIfLive("run-1", RunStatus.CANCELLED, null, "cancelled while queued", NOW);

    assertThat(applied).isTrue();
    List<RunnerEvent> recorded = store.readEventsAfter("run-1", 0);
    assertThat(recorded)
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.RUN_QUEUED, EventType.RUN_FINISHED);
    assertThat(recorded).extracting(RunnerEvent::sequence).containsExactly(1L, 2L);
    assertThat(recorded.getLast().runOutcome()).isEqualTo(RunOutcome.CANCELLED);
  }

  /** Same reasoning as {@link #aFailingRunStartedWriteLeavesTheRunInStartingUnchanged}. */
  @Test
  void aFailingRunFinishedWriteLeavesTheRunRunningNeverSucceeded() {
    RunLifecycleStore failingStore = new FailingRunLifecycleStore(store, EventType.RUN_FINISHED);
    RunLifecycleCoordinator failingCoordinator =
        new RunLifecycleCoordinator(newBroker(failingStore), noopArtifactIngestionService());
    failingCoordinator.queue("run-1", Environment.PUBLIC, Suite.SMOKE, NOW);
    failingCoordinator.markStarting("run-1", NOW);
    failingCoordinator.markRunning("run-1", NOW);

    assertThatThrownBy(
            () -> failingCoordinator.finishIfLive("run-1", RunStatus.SUCCEEDED, 0, null, NOW))
        .isInstanceOf(UncheckedIOException.class);

    Run unchanged = store.findById("run-1").orElseThrow();
    assertThat(unchanged.status()).isEqualTo(RunStatus.RUNNING);
    assertThat(store.readEventsAfter("run-1", 0))
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.RUN_QUEUED, EventType.RUN_STARTED);
  }

  /**
   * Regression test for the review's finding: of two callers racing to finalize the same run, only
   * the one whose transition actually applies may emit RUN_FINISHED - a race must never produce
   * more than one.
   */
  @Test
  void concurrentFinishAttemptsNeverProduceMoreThanOneRunFinished() throws Exception {
    coordinator.queue("run-1", Environment.PUBLIC, Suite.SMOKE, NOW);
    coordinator.markStarting("run-1", NOW);
    coordinator.markRunning("run-1", NOW);

    int attempts = 16;
    ExecutorService executor = Executors.newFixedThreadPool(attempts);
    CountDownLatch ready = new CountDownLatch(attempts);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> futures = new ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      RunStatus status = i % 2 == 0 ? RunStatus.SUCCEEDED : RunStatus.FAILED;
      futures.add(
          executor.submit(
              () -> {
                ready.countDown();
                awaitUninterruptibly(start);
                return coordinator.finishIfLive("run-1", status, 0, "attempt", NOW);
              }));
    }
    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    executor.shutdown();
    int appliedCount = 0;
    for (Future<Boolean> future : futures) {
      if (future.get(30, TimeUnit.SECONDS)) {
        appliedCount++;
      }
    }
    assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

    assertThat(appliedCount).isEqualTo(1);
    List<RunnerEvent> finished =
        store.readEventsAfter("run-1", 0).stream()
            .filter(event -> event.type() == EventType.RUN_FINISHED)
            .toList();
    assertThat(finished).hasSize(1);
  }

  @Test
  void finishIfLiveRejectsANonTerminalStatusWithoutMutatingTheRun() {
    coordinator.queue("run-1", Environment.PUBLIC, Suite.SMOKE, NOW);

    assertThatThrownBy(() -> coordinator.finishIfLive("run-1", RunStatus.RUNNING, null, null, NOW))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(store.findById("run-1").orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
    assertThat(store.readEventsAfter("run-1", 0))
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.RUN_QUEUED);
  }

  private RunnerEvent lastEvent(String runId) {
    List<RunnerEvent> events = store.readEventsAfter(runId, 0);
    return events.get(events.size() - 1);
  }

  private RunOutcome expectedOutcome(RunStatus status) {
    return switch (status) {
      case SUCCEEDED -> RunOutcome.SUCCEEDED;
      case FAILED -> RunOutcome.FAILED;
      case CANCELLED -> RunOutcome.CANCELLED;
      case TIMED_OUT -> RunOutcome.TIMED_OUT;
      case ERROR -> RunOutcome.ERROR;
      default -> throw new AssertionError("Not exercised: " + status);
    };
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  private static RunEventBroker newBroker(RunLifecycleStore store) {
    return new RunEventBroker(store, testProperties(), noopArtifactIngestionService());
  }

  /**
   * None of this test class's scenarios ever append a {@code TEST_FAILED}/{@code TEST_ABORTED}
   * event (this class only exercises lifecycle transitions, never the {@code TEST_*}/{@code STEP_*}
   * append path), so {@link RunEventBroker}'s own D2.4 artifact-ingestion hook never actually fires
   * here - a real {@link ArtifactIngestionService} wired to an in-memory {@link
   * FakeArtifactRepository} satisfies the constructor without needing a real manifest file or
   * database.
   */
  private static ArtifactIngestionService noopArtifactIngestionService() {
    return new ArtifactIngestionService(
        new ObjectMapper(), new FakeArtifactRepository(), testProperties());
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
        16384,
        Duration.ofDays(30),
        500,
        Duration.ofDays(14),
        Duration.ofHours(1),
        new RateLimitRule(10, Duration.ofHours(1)),
        1_048_576L,
        26_214_400L,
        209_715_200L,
        2_097_152L,
        2_097_152L,
        104_857_600L,
        new RateLimitRule(10, Duration.ofHours(1)));
  }
}
