package dev.vlaisanem.automation.runner.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import dev.vlaisanem.automation.runner.service.exception.RunnerRecoveringException;
import dev.vlaisanem.automation.runner.service.repository.FakeRunLifecycleStore;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * D2.5 (docs/DEPLOYMENT_ARCHITECTURE.md's "Restart behavior" section) - proves {@link
 * RunRecoveryService} recovers every non-terminal run to {@code ERROR} (never touching an
 * already-terminal one), is idempotent under a repeated pass, and is fail-closed: {@link
 * RunRecoveryService#requireRecoveryComplete} never stops throwing once a single run - or the
 * initial load itself - fails to recover, and the pass itself fails loudly (by throwing out of
 * {@link RunRecoveryService#run}) rather than ever declaring itself done with unfinished work left
 * behind.
 */
class RunRecoveryServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

  private final FakeRunLifecycleStore store = new FakeRunLifecycleStore();
  private final RunLifecycleCoordinator coordinator = newCoordinator(store);

  @Test
  void recoversEveryNonTerminalRunToErrorAndLeavesTerminalRunsUntouched() {
    coordinator.queue("queued-run", Environment.PUBLIC, Suite.SMOKE, NOW);

    coordinator.queue("starting-run", Environment.PUBLIC, Suite.SMOKE, NOW);
    coordinator.markStarting("starting-run", NOW);

    coordinator.queue("running-run", Environment.PUBLIC, Suite.SMOKE, NOW);
    coordinator.markStarting("running-run", NOW);
    coordinator.markRunning("running-run", NOW);

    coordinator.queue("succeeded-run", Environment.PUBLIC, Suite.SMOKE, NOW);
    coordinator.markStarting("succeeded-run", NOW);
    coordinator.markRunning("succeeded-run", NOW);
    coordinator.finishIfLive("succeeded-run", RunStatus.SUCCEEDED, 0, null, NOW);

    RunRecoveryService recovery = new RunRecoveryService(store, coordinator);
    recovery.run(null);

    for (String recoveredRunId : List.of("queued-run", "starting-run", "running-run")) {
      Run run = store.findById(recoveredRunId).orElseThrow();
      assertThat(run.status()).as(recoveredRunId).isEqualTo(RunStatus.ERROR);
      assertThat(run.detail()).as(recoveredRunId).contains("restarted");
      RunnerEvent last = store.readEventsAfter(recoveredRunId, 0).getLast();
      assertThat(last.type()).as(recoveredRunId).isEqualTo(EventType.RUN_FINISHED);
      assertThat(last.runOutcome()).as(recoveredRunId).isEqualTo(RunOutcome.ERROR);
    }

    // The already-terminal run must be completely untouched - same status, same event count.
    Run succeeded = store.findById("succeeded-run").orElseThrow();
    assertThat(succeeded.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(store.readEventsAfter("succeeded-run", 0)).hasSize(3); // QUEUED, STARTED, FINISHED
  }

  @Test
  void isIdempotentOnASecondRecoveryPass() {
    coordinator.queue("running-run", Environment.PUBLIC, Suite.SMOKE, NOW);
    coordinator.markStarting("running-run", NOW);
    coordinator.markRunning("running-run", NOW);
    RunRecoveryService recovery = new RunRecoveryService(store, coordinator);
    recovery.run(null);
    int eventCountAfterFirstPass = store.readEventsAfter("running-run", 0).size();

    // A second pass (modeling a second restart) must find nothing left to do - the run is already
    // terminal, so it no longer matches findNonTerminal() at all, never re-processed.
    RunRecoveryService secondRecovery = new RunRecoveryService(store, coordinator);
    assertThatCode(() -> secondRecovery.run(null)).doesNotThrowAnyException();

    Run run = store.findById("running-run").orElseThrow();
    assertThat(run.status()).isEqualTo(RunStatus.ERROR);
    assertThat(store.readEventsAfter("running-run", 0)).hasSize(eventCountAfterFirstPass);
  }

  @Test
  void requireRecoveryCompleteThrowsBeforeThePassHasRun() {
    RunRecoveryService recovery = new RunRecoveryService(store, coordinator);

    assertThatThrownBy(recovery::requireRecoveryComplete)
        .isInstanceOf(RunnerRecoveringException.class);
  }

  @Test
  void requireRecoveryCompleteDoesNotThrowOnceThePassHasRun() {
    RunRecoveryService recovery = new RunRecoveryService(store, coordinator);

    recovery.run(null);

    assertThatCode(recovery::requireRecoveryComplete).doesNotThrowAnyException();
  }

  /**
   * D2.5 review [P1] - a run whose own recovery attempt fails must not prevent every other run from
   * still being recovered, but the pass as a whole must not lie about being complete: it must throw
   * (so an {@code ApplicationRunner} failure fails startup and the container orchestrator retries),
   * and {@link RunRecoveryService#requireRecoveryComplete} must keep rejecting traffic with a
   * {@code 503} forever after - never fail open just because most of the pass succeeded.
   */
  @Test
  void aFailureRecoveringOneRunStillRecoversTheOthersButFailsThePassAndKeepsTheGateClosed() {
    coordinator.queue("good-run", Environment.PUBLIC, Suite.SMOKE, NOW);
    coordinator.markStarting("good-run", NOW);

    coordinator.queue("bad-run", Environment.PUBLIC, Suite.SMOKE, NOW);
    coordinator.markStarting("bad-run", NOW);

    RunLifecycleStore failingForBadRun = failingTransitionFor(store, "bad-run");
    RunLifecycleCoordinator failingCoordinator = newCoordinator(failingForBadRun);
    RunRecoveryService recovery = new RunRecoveryService(failingForBadRun, failingCoordinator);

    assertThatThrownBy(() -> recovery.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bad-run")
        .satisfies(
            thrown ->
                assertThat(thrown.getSuppressed())
                    .as(
                        "the run's own original exception must survive as a suppressed cause, not"
                            + " only in the log line")
                    .singleElement()
                    .satisfies(
                        cause ->
                            assertThat(cause).hasMessageContaining("simulated recovery failure")));

    assertThat(store.findById("good-run").orElseThrow().status()).isEqualTo(RunStatus.ERROR);
    assertThat(store.findById("bad-run").orElseThrow().status())
        .as("the run whose own recovery attempt failed stays at its old, stale status")
        .isEqualTo(RunStatus.STARTING);
    assertThatThrownBy(recovery::requireRecoveryComplete)
        .as("the gate must stay closed - the pass did not genuinely finish")
        .isInstanceOf(RunnerRecoveringException.class);
  }

  /**
   * D2.5 review [P1] - the same fail-closed contract must hold when the initial load of
   * non-terminal runs itself fails (e.g. a transient database error), not only when one individual
   * run's own recovery fails.
   */
  @Test
  void aFailureLoadingTheNonTerminalRunsFailsThePassAndKeepsTheGateClosed() {
    RunLifecycleStore brokenLoad =
        new RunLifecycleStore() {
          @Override
          public dev.vlaisanem.automation.runner.service.repository.CommittedRunChange queue(
              String runId,
              Environment environment,
              Suite suite,
              Instant requestedAt,
              List<dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot>
                  selectedTests,
              LongFunction<RunnerEvent> queuedEventFactory) {
            return store.queue(
                runId, environment, suite, requestedAt, selectedTests, queuedEventFactory);
          }

          @Override
          public Optional<dev.vlaisanem.automation.runner.service.repository.CommittedRunChange>
              transitionIfNonTerminal(
                  String runId,
                  UnaryOperator<Run> transition,
                  LongFunction<RunnerEvent> eventFactory) {
            return store.transitionIfNonTerminal(runId, transition, eventFactory);
          }

          @Override
          public Optional<RunnerEvent> appendEventIfNonTerminal(
              String runId, LongFunction<RunnerEvent> eventFactory) {
            return store.appendEventIfNonTerminal(runId, eventFactory);
          }

          @Override
          public Optional<Run> findById(String runId) {
            return store.findById(runId);
          }

          @Override
          public List<Run> findAll() {
            return store.findAll();
          }

          @Override
          public List<Run> findNonTerminal() {
            throw new IllegalStateException("simulated database failure loading non-terminal runs");
          }

          @Override
          public List<RunnerEvent> readEventsAfter(String runId, long afterSequence) {
            return store.readEventsAfter(runId, afterSequence);
          }

          @Override
          public Optional<RunnerEvent> latestEvent(String runId) {
            return store.latestEvent(runId);
          }

          @Override
          public List<String> findEligibleForCleanup(Instant now, Duration maxAge, int maxCount) {
            return store.findEligibleForCleanup(now, maxAge, maxCount);
          }

          @Override
          public List<String> findPendingCleanup() {
            return store.findPendingCleanup();
          }

          @Override
          public boolean claimForCleanup(String runId) {
            return store.claimForCleanup(runId);
          }

          @Override
          public void deleteRun(String runId) {
            store.deleteRun(runId);
          }

          @Override
          public List<String> findEligibleForArtifactPurge(Instant now, Duration maxAge) {
            return store.findEligibleForArtifactPurge(now, maxAge);
          }

          @Override
          public List<String> findPendingArtifactPurge() {
            return store.findPendingArtifactPurge();
          }

          @Override
          public boolean claimForArtifactPurge(String runId) {
            return store.claimForArtifactPurge(runId);
          }
        };

    RunRecoveryService recovery = new RunRecoveryService(brokenLoad, coordinator);

    assertThatThrownBy(() -> recovery.run(null)).isInstanceOf(IllegalStateException.class);

    assertThatThrownBy(recovery::requireRecoveryComplete)
        .isInstanceOf(RunnerRecoveringException.class);
  }

  /**
   * Wraps {@code delegate}, failing every {@link RunLifecycleStore#transitionIfNonTerminal} call
   * for exactly {@code failingRunId}, delegating every other run and every other method straight
   * through - the minimal shape needed to prove one run's own recovery failure does not derail the
   * rest of the pass.
   */
  private static RunLifecycleStore failingTransitionFor(
      RunLifecycleStore delegate, String failingRunId) {
    return new RunLifecycleStore() {
      @Override
      public dev.vlaisanem.automation.runner.service.repository.CommittedRunChange queue(
          String runId,
          Environment environment,
          Suite suite,
          Instant requestedAt,
          List<dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot> selectedTests,
          LongFunction<RunnerEvent> queuedEventFactory) {
        return delegate.queue(
            runId, environment, suite, requestedAt, selectedTests, queuedEventFactory);
      }

      @Override
      public Optional<dev.vlaisanem.automation.runner.service.repository.CommittedRunChange>
          transitionIfNonTerminal(
              String runId, UnaryOperator<Run> transition, LongFunction<RunnerEvent> eventFactory) {
        if (failingRunId.equals(runId)) {
          throw new IllegalStateException("simulated recovery failure for " + runId);
        }
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
    };
  }

  private static RunLifecycleCoordinator newCoordinator(RunLifecycleStore store) {
    RunnerProperties properties = testProperties();
    ArtifactIngestionService artifactIngestionService =
        new ArtifactIngestionService(new ObjectMapper(), new FakeArtifactRepository(), properties);
    RunEventBroker broker = new RunEventBroker(store, properties, artifactIngestionService);
    return new RunLifecycleCoordinator(broker, artifactIngestionService);
  }

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
