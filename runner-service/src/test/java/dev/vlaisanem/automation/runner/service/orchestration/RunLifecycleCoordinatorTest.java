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
import dev.vlaisanem.automation.runner.service.metrics.RunnerMetrics;
import dev.vlaisanem.automation.runner.service.repository.FailingRunLifecycleStore;
import dev.vlaisanem.automation.runner.service.repository.FakeRunLifecycleStore;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * Verifies {@link RunLifecycleCoordinator}'s state transitions and event emission against {@link
 * FakeRunLifecycleStore}. A failing store write leaves the run completely unchanged (one atomic
 * transaction, no separate emergency fallback), and the exception propagates for {@code RunService}
 * to handle.
 */
class RunLifecycleCoordinatorTest {

  private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

  private final FakeRunLifecycleStore store = new FakeRunLifecycleStore();
  private final RunEventBroker broker = newBroker(store);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final RunnerMetrics metrics = new RunnerMetrics(meterRegistry);
  private final RunLifecycleCoordinator coordinator =
      new RunLifecycleCoordinator(broker, noopArtifactIngestionService(), metrics);

  @Test
  void queueSavesTheRunAndEmitsRunQueuedFirst() {
    Run run = coordinator.queue("run-1", Environment.PUBLIC, Suite.SMOKE, NOW);

    assertThat(run.status()).isEqualTo(RunStatus.QUEUED);
    List<RunnerEvent> recorded = store.readEventsAfter("run-1", 0);
    assertThat(recorded).hasSize(1);
    assertThat(recorded.getFirst().type()).isEqualTo(EventType.RUN_QUEUED);
    assertThat(recorded.getFirst().sequence()).isEqualTo(1L);
    assertThat(meterRegistry.find("runner.runs.submitted").tag("suite", "SMOKE").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void aFailingQueueWriteLeavesNoRunBehind() {
    RunLifecycleStore failingStore = new FailingRunLifecycleStore(store, EventType.RUN_QUEUED);
    RunLifecycleCoordinator failingCoordinator =
        new RunLifecycleCoordinator(
            newBroker(failingStore), noopArtifactIngestionService(), metrics);

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
    assertThat(meterRegistry.find("runner.runs.started").tag("suite", "SMOKE").counter().count())
        .isEqualTo(1.0);
  }

  /**
   * With one atomic store transaction, a failed write leaves the run completely unchanged instead
   * of a status change un-backed by its event; the failure propagates for {@code RunService}'s own
   * fallback to handle.
   */
  @Test
  void aFailingRunStartedWriteLeavesTheRunInStartingUnchanged() {
    RunLifecycleStore failingStore = new FailingRunLifecycleStore(store, EventType.RUN_STARTED);
    RunLifecycleCoordinator failingCoordinator =
        new RunLifecycleCoordinator(
            newBroker(failingStore), noopArtifactIngestionService(), metrics);
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
   * A run whose transition is lost to a concurrent finalization must never emit a RUN_STARTED it
   * cannot honestly back up.
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
    assertThat(meterRegistry.find("runner.runs.started").counter()).isNull();
    assertThat(
            meterRegistry
                .find("runner.runs.finished")
                .tag("suite", "SMOKE")
                .tag("status", "CANCELLED")
                .counter()
                .count())
        .isEqualTo(1.0);
    // Cancelled while still STARTING - startedAt is null, so no duration is ever recorded for it.
    assertThat(
            meterRegistry
                .find("runner.runs.duration")
                .tag("suite", "SMOKE")
                .tag("status", "CANCELLED")
                .timer())
        .isNull();
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
      assertThat(
              meterRegistry
                  .find("runner.runs.finished")
                  .tag("suite", "SMOKE")
                  .tag("status", status.name())
                  .counter()
                  .count())
          .isEqualTo(1.0);
      // markRunning ran before finishIfLive for every status here, so startedAt is always
      // non-null - a duration is recorded for every one of these terminal statuses.
      assertThat(
              meterRegistry
                  .find("runner.runs.duration")
                  .tag("suite", "SMOKE")
                  .tag("status", status.name())
                  .timer()
                  .count())
          .isEqualTo(1L);
    }
  }

  /**
   * A run cancelled before reaching STARTING/RUNNING must emit only RUN_QUEUED then {@code
   * RUN_FINISHED(CANCELLED)} - no RUN_STARTED or TEST_* event, with a continuous sequence.
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
    assertThat(
            meterRegistry
                .find("runner.runs.finished")
                .tag("suite", "SMOKE")
                .tag("status", "CANCELLED")
                .counter()
                .count())
        .isEqualTo(1.0);
    // Cancelled straight from QUEUED - never reached RUNNING, so startedAt is null and no
    // duration is ever recorded for it.
    assertThat(
            meterRegistry
                .find("runner.runs.duration")
                .tag("suite", "SMOKE")
                .tag("status", "CANCELLED")
                .timer())
        .isNull();
  }

  /** Same reasoning as {@link #aFailingRunStartedWriteLeavesTheRunInStartingUnchanged}. */
  @Test
  void aFailingRunFinishedWriteLeavesTheRunRunningNeverSucceeded() {
    RunLifecycleStore failingStore = new FailingRunLifecycleStore(store, EventType.RUN_FINISHED);
    RunLifecycleCoordinator failingCoordinator =
        new RunLifecycleCoordinator(
            newBroker(failingStore), noopArtifactIngestionService(), metrics);
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
   * Of many callers racing to finalize the same run, only the one whose transition applies may emit
   * RUN_FINISHED - a race must never produce more than one.
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
    // Exactly one of the 16 attempts wins; the metric (summed across whichever status tag it used)
    // must have fired exactly once, never once per attempt.
    double totalFinished =
        meterRegistry.find("runner.runs.finished").counters().stream()
            .mapToDouble(Counter::count)
            .sum();
    assertThat(totalFinished).isEqualTo(1.0);
    long totalDurationCount =
        meterRegistry.find("runner.runs.duration").timers().stream().mapToLong(Timer::count).sum();
    assertThat(totalDurationCount).isEqualTo(1L);
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
    return new RunEventBroker(
        store,
        testProperties(),
        noopArtifactIngestionService(),
        new RunnerMetrics(new SimpleMeterRegistry()),
        new SimpleMeterRegistry());
  }

  /**
   * This class only exercises lifecycle transitions, never TEST_* or STEP_* events, so {@link
   * RunEventBroker}'s artifact-ingestion hook never fires - an in-memory {@link
   * FakeArtifactRepository} is enough to satisfy the constructor.
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
        new RateLimitRule(10, Duration.ofHours(1)),
        Duration.ofSeconds(60));
  }
}
