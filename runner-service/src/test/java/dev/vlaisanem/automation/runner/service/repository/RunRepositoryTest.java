package dev.vlaisanem.automation.runner.service.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RunRepositoryTest {

  @Test
  void savesAndFindsByRunId() {
    RunRepository repository = new RunRepository();
    Run run = Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, Instant.now());

    repository.save(run);

    assertThat(repository.findById("run-1")).contains(run);
    assertThat(repository.findById("missing")).isEmpty();
  }

  @Test
  void saveDoesNotExposeTheRunWhenItsBeforeCommitActionFails() {
    RunRepository repository = new RunRepository();
    Run run = Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, Instant.now());

    assertThatThrownBy(
            () ->
                repository.save(
                    run,
                    ignored -> {
                      throw new IllegalStateException("simulated pre-commit failure");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pre-commit");

    assertThat(repository.findById("run-1")).isEmpty();
  }

  @Test
  void findAllReturnsMostRecentlyRequestedFirst() {
    RunRepository repository = new RunRepository();
    Instant t0 = Instant.parse("2026-08-29T12:00:00Z");
    Run older = Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, t0);
    Run newer = Run.queued("run-2", Environment.PUBLIC, Suite.API, t0.plusSeconds(10));

    repository.save(older);
    repository.save(newer);

    assertThat(repository.findAll()).containsExactly(newer, older);
  }

  @Test
  void transitionAppliesTheFunctionToTheStoredSnapshot() {
    RunRepository repository = new RunRepository();
    Instant t0 = Instant.now();
    repository.save(Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, t0));

    Run updated =
        repository.transition(
            "run-1", run -> run.transitionTo(RunStatus.STARTING, t0.plusSeconds(1)));

    assertThat(updated.status()).isEqualTo(RunStatus.STARTING);
    assertThat(repository.findById("run-1")).contains(updated);
  }

  @Test
  void saveRejectsADuplicateRunId() {
    RunRepository repository = new RunRepository();
    Instant t0 = Instant.now();
    repository.save(Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, t0));

    assertThatThrownBy(
            () -> repository.save(Run.queued("run-1", Environment.PUBLIC, Suite.API, t0)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("run-1");
    // The original must survive untouched - putIfAbsent, not put, backs save().
    assertThat(repository.findById("run-1")).get().extracting(Run::suite).isEqualTo(Suite.SMOKE);
  }

  @Test
  void transitionRejectsANullResultAndLeavesTheRunInPlace() {
    RunRepository repository = new RunRepository();
    Instant t0 = Instant.now();
    Run original = Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, t0);
    repository.save(original);

    assertThatThrownBy(() -> repository.transition("run-1", run -> null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("run-1");
    // ConcurrentHashMap.compute() would otherwise treat a null result as "remove this key" - the
    // run must still be there, not silently deleted.
    assertThat(repository.findById("run-1")).contains(original);
  }

  @Test
  void transitionThrowsForAnUnknownRunId() {
    RunRepository repository = new RunRepository();

    assertThatThrownBy(
            () ->
                repository.transition(
                    "missing", run -> run.transitionTo(RunStatus.STARTING, Instant.now())))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void transitionIfNonTerminalReturnsFalseWithoutThrowingWhenAlreadyTerminal() {
    RunRepository repository = new RunRepository();
    Instant t0 = Instant.now();
    Run terminal =
        Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, t0)
            .transitionTo(RunStatus.STARTING, t0)
            .transitionTo(RunStatus.RUNNING, t0)
            .transitionTo(RunStatus.SUCCEEDED, t0, 0, null);
    repository.save(terminal);

    boolean applied =
        repository.transitionIfNonTerminal(
            "run-1", run -> run.transitionTo(RunStatus.CANCELLED, Instant.now()));

    assertThat(applied).isFalse();
    assertThat(repository.findById("run-1")).contains(terminal);
  }

  /**
   * Regression test for the review's finding: {@code transitionIfNonTerminal} must only swallow the
   * specific, expected "already terminal" race - a genuinely invalid transition for any other
   * reason (a real bug) must still propagate instead of being silently treated the same way, or a
   * bug could leave a run stuck non-terminal forever with no visible failure.
   */
  @Test
  void transitionIfNonTerminalPropagatesAGenuinelyInvalidTransition() {
    RunRepository repository = new RunRepository();
    repository.save(Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, Instant.now()));

    // QUEUED -> SUCCEEDED skips STARTING/RUNNING entirely - invalid for a reason unrelated to
    // terminality, so it must not be swallowed the way an "already terminal" race is.
    assertThatThrownBy(
            () ->
                repository.transitionIfNonTerminal(
                    "run-1", run -> run.transitionTo(RunStatus.SUCCEEDED, Instant.now(), 0, null)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void transitionDoesNotExposeTheNewSnapshotWhenItsBeforeCommitActionFails() {
    RunRepository repository = new RunRepository();
    Instant now = Instant.now();
    Run original = Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, now);
    repository.save(original);

    assertThatThrownBy(
            () ->
                repository.transitionIfNonTerminal(
                    "run-1",
                    run -> run.transitionTo(RunStatus.STARTING, now),
                    ignored -> {
                      throw new IllegalStateException("simulated pre-commit failure");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pre-commit");

    assertThat(repository.findById("run-1")).contains(original);
  }

  /**
   * Regression test for the exact race the review flagged: without {@link
   * java.util.concurrent.ConcurrentHashMap#compute} serializing per-key, several threads racing to
   * transition the same run could all read the same starting snapshot, all pass validation, and
   * silently overwrite each other - meaning several "successful" transitions instead of exactly
   * one. With the fix, exactly one thread's transition applies to the true RUNNING state; every
   * other thread sees that already-applied SUCCEEDED result and its own attempted transition (which
   * would be a no-op SUCCEEDED -> SUCCEEDED) correctly fails as invalid.
   */
  @Test
  void transitionSerializesConcurrentAttemptsOnTheSameRun() throws Exception {
    RunRepository repository = new RunRepository();
    Instant t0 = Instant.now();
    repository.save(
        Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, t0)
            .transitionTo(RunStatus.STARTING, t0)
            .transitionTo(RunStatus.RUNNING, t0));

    int threadCount = 8;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger succeeded = new AtomicInteger();
    AtomicInteger rejected = new AtomicInteger();

    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      futures.add(
          executor.submit(
              () -> {
                ready.countDown();
                awaitUninterruptibly(start);
                try {
                  repository.transition(
                      "run-1", run -> run.transitionTo(RunStatus.SUCCEEDED, Instant.now()));
                  succeeded.incrementAndGet();
                } catch (IllegalStateException expected) {
                  rejected.incrementAndGet();
                }
              }));
    }

    ready.await();
    start.countDown();
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    executor.shutdown();
    assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

    assertThat(succeeded.get()).isEqualTo(1);
    assertThat(rejected.get()).isEqualTo(threadCount - 1);
    assertThat(repository.findById("run-1"))
        .get()
        .extracting(Run::status)
        .isEqualTo(RunStatus.SUCCEEDED);
  }

  private void awaitUninterruptibly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }
}
