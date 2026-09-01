package dev.vlaisanem.automation.runner.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunOutcome;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.events.FailingRunEventAppender;
import dev.vlaisanem.automation.runner.service.events.RecordingRunEventAppender;
import dev.vlaisanem.automation.runner.service.exception.RunEventPersistenceException;
import dev.vlaisanem.automation.runner.service.repository.RunRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RunLifecycleCoordinatorTest {

  private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

  private final RunRepository repository = new RunRepository();
  private final RecordingRunEventAppender events = new RecordingRunEventAppender();
  private final RunLifecycleCoordinator coordinator =
      new RunLifecycleCoordinator(repository, events);

  @Test
  void queueSavesTheRunAndEmitsRunQueuedFirst() {
    Run run = coordinator.queue("run-1", Environment.PUBLIC, Suite.SMOKE, NOW);

    assertThat(run.status()).isEqualTo(RunStatus.QUEUED);
    List<RunnerEvent> recorded = events.eventsFor("run-1");
    assertThat(recorded).hasSize(1);
    assertThat(recorded.getFirst().type()).isEqualTo(EventType.RUN_QUEUED);
    assertThat(recorded.getFirst().sequence()).isEqualTo(1L);
  }

  @Test
  void aRunQueuedEventFailureDoesNotAcceptAZombieRunAndClosesTheJournalDependency() {
    RunRepository failingRepository = new RunRepository();
    RunLifecycleCoordinator failingCoordinator =
        new RunLifecycleCoordinator(
            failingRepository, new FailingRunEventAppender(EventType.RUN_QUEUED));

    assertThatThrownBy(
            () -> failingCoordinator.queue("run-1", Environment.PUBLIC, Suite.SMOKE, NOW))
        .isInstanceOf(RunEventPersistenceException.class);
    assertThat(failingRepository.findById("run-1")).isEmpty();

    assertThatThrownBy(() -> failingCoordinator.queue("run-2", Environment.PUBLIC, Suite.API, NOW))
        .isInstanceOf(RunEventPersistenceException.class);
    assertThat(failingRepository.findById("run-2")).isEmpty();
  }

  @Test
  void markStartingAppliesTheTransitionWithoutEmittingAnyEvent() {
    coordinator.queue("run-1", Environment.PUBLIC, Suite.SMOKE, NOW);

    boolean applied = coordinator.markStarting("run-1", NOW);

    assertThat(applied).isTrue();
    assertThat(repository.findById("run-1").orElseThrow().status()).isEqualTo(RunStatus.STARTING);
    assertThat(events.eventsFor("run-1"))
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.RUN_QUEUED);
  }

  @Test
  void markRunningAppliesTheTransitionAndEmitsRunStarted() {
    coordinator.queue("run-1", Environment.PUBLIC, Suite.SMOKE, NOW);
    coordinator.markStarting("run-1", NOW);

    boolean applied = coordinator.markRunning("run-1", NOW);

    assertThat(applied).isTrue();
    assertThat(events.eventsFor("run-1"))
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.RUN_QUEUED, EventType.RUN_STARTED);
  }

  @Test
  void aRunStartedEventFailureCommitsOnlyAnEmergencyErrorSnapshot() {
    RunRepository failingRepository = new RunRepository();
    FailingRunEventAppender failingEvents = new FailingRunEventAppender(EventType.RUN_STARTED);
    RunLifecycleCoordinator failingCoordinator =
        new RunLifecycleCoordinator(failingRepository, failingEvents);
    failingCoordinator.queue("run-1", Environment.PUBLIC, Suite.SMOKE, NOW);
    failingCoordinator.markStarting("run-1", NOW);

    assertThatThrownBy(() -> failingCoordinator.markRunning("run-1", NOW))
        .isInstanceOf(RunEventPersistenceException.class);

    Run emergency = failingRepository.findById("run-1").orElseThrow();
    assertThat(emergency.status()).isEqualTo(RunStatus.ERROR);
    assertThat(emergency.startedAt()).isNull();
    assertThat(emergency.detail()).contains("event timeline is incomplete");
    assertThat(failingEvents.eventsFor("run-1"))
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
    assertThat(events.eventsFor("run-1"))
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
      RunnerEvent finished = events.eventsFor(runId).getLast();
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
    List<RunnerEvent> recorded = events.eventsFor("run-1");
    assertThat(recorded)
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.RUN_QUEUED, EventType.RUN_FINISHED);
    assertThat(recorded).extracting(RunnerEvent::sequence).containsExactly(1L, 2L);
    assertThat(recorded.getLast().runOutcome()).isEqualTo(RunOutcome.CANCELLED);
  }

  @Test
  void aRunFinishedEventFailureCanNeverLeaveTheRunSucceeded() {
    RunRepository failingRepository = new RunRepository();
    FailingRunEventAppender failingEvents = new FailingRunEventAppender(EventType.RUN_FINISHED);
    RunLifecycleCoordinator failingCoordinator =
        new RunLifecycleCoordinator(failingRepository, failingEvents);
    failingCoordinator.queue("run-1", Environment.PUBLIC, Suite.SMOKE, NOW);
    failingCoordinator.markStarting("run-1", NOW);
    failingCoordinator.markRunning("run-1", NOW);

    assertThatThrownBy(
            () -> failingCoordinator.finishIfLive("run-1", RunStatus.SUCCEEDED, 0, null, NOW))
        .isInstanceOf(RunEventPersistenceException.class);

    Run emergency = failingRepository.findById("run-1").orElseThrow();
    assertThat(emergency.status()).isEqualTo(RunStatus.ERROR);
    assertThat(emergency.exitCode()).isNull();
    assertThat(emergency.detail()).contains("event timeline is incomplete");
    assertThat(failingEvents.eventsFor("run-1"))
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
        events.eventsFor("run-1").stream()
            .filter(event -> event.type() == EventType.RUN_FINISHED)
            .toList();
    assertThat(finished).hasSize(1);
  }

  @Test
  void finishIfLiveRejectsANonTerminalStatusWithoutMutatingTheRun() {
    coordinator.queue("run-1", Environment.PUBLIC, Suite.SMOKE, NOW);

    assertThatThrownBy(() -> coordinator.finishIfLive("run-1", RunStatus.RUNNING, null, null, NOW))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(repository.findById("run-1").orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
    assertThat(events.eventsFor("run-1"))
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.RUN_QUEUED);
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
}
