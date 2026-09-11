package dev.vlaisanem.automation.runner.service.repository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vlaisanem.automation.runner.contract.RunOutcome;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fast, DB-free proof of {@link RunEventValidation}'s checks - also exercised end-to-end against a
 * real Postgres in {@code JdbcRunStoreTest}, but this proves the shared logic itself, not just one
 * caller of it, and runs in the default fast suite.
 */
class RunEventValidationTest {

  private static final String RUN_ID = "run-1";
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void startingRejectsANonNullEvent() {
    RunnerEvent event = RunnerEvent.runStarted(RUN_ID, 2L, NOW);

    assertThatThrownBy(
            () -> RunEventValidation.requireLifecycleEventMatches(event, RunStatus.STARTING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not carry an event");
  }

  @Test
  void startingAcceptsANullEvent() {
    assertThatCode(() -> RunEventValidation.requireLifecycleEventMatches(null, RunStatus.STARTING))
        .doesNotThrowAnyException();
  }

  @Test
  void runningRejectsANullEvent() {
    assertThatThrownBy(
            () -> RunEventValidation.requireLifecycleEventMatches(null, RunStatus.RUNNING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RUN_STARTED");
  }

  @Test
  void terminalRejectsANullEvent() {
    assertThatThrownBy(
            () -> RunEventValidation.requireLifecycleEventMatches(null, RunStatus.SUCCEEDED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RUN_FINISHED");
  }

  @Test
  void queuedIsNotAValidTransitionTarget() {
    assertThatThrownBy(
            () ->
                RunEventValidation.requireLifecycleEventMatches(
                    RunnerEvent.runQueued(RUN_ID, 1L, NOW), RunStatus.QUEUED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("QUEUED");
  }

  @Test
  void requireSameIdentityAcceptsAStatusOnlyChange() {
    Run before = Run.queued(RUN_ID, Environment.PUBLIC, Suite.SMOKE, NOW, List.of());
    Run after = before.transitionTo(RunStatus.STARTING, NOW.plusSeconds(1));

    assertThatCode(() -> RunEventValidation.requireSameIdentity(before, after))
        .doesNotThrowAnyException();
  }

  @Test
  void requireSameIdentityRejectsAChangedRequestedAt() {
    Run before = Run.queued(RUN_ID, Environment.PUBLIC, Suite.SMOKE, NOW, List.of());
    Run after =
        new Run(
            before.runId(),
            before.environment(),
            before.suite(),
            RunStatus.STARTING,
            before.requestedAt().plusSeconds(999),
            null,
            null,
            null,
            null,
            before.selectedTests());

    assertThatThrownBy(() -> RunEventValidation.requireSameIdentity(before, after))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("runId/environment/suite/requestedAt/selectedTests");
  }

  @Test
  void requireSameIdentityRejectsAChangedRunId() {
    Run before = Run.queued(RUN_ID, Environment.PUBLIC, Suite.SMOKE, NOW, List.of());
    Run after =
        new Run(
            "a-different-run-id",
            before.environment(),
            before.suite(),
            before.status(),
            before.requestedAt(),
            before.startedAt(),
            before.finishedAt(),
            before.exitCode(),
            before.detail(),
            before.selectedTests());

    assertThatThrownBy(() -> RunEventValidation.requireSameIdentity(before, after))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requireReachableTransitionRejectsASkippedStateMachineEdge() {
    Run before = Run.queued(RUN_ID, Environment.PUBLIC, Suite.SMOKE, NOW, List.of());
    // A hand-rolled Run built directly, not via Run.transitionTo, so RunStateMachine is never
    // consulted - QUEUED -> SUCCEEDED skips STARTING/RUNNING entirely, which transitionTo itself
    // would reject but nothing else here would catch without this check.
    Run after =
        new Run(
            before.runId(),
            before.environment(),
            before.suite(),
            RunStatus.SUCCEEDED,
            before.requestedAt(),
            NOW,
            NOW,
            0,
            null,
            before.selectedTests());

    assertThatThrownBy(() -> RunEventValidation.requireReachableTransition(before, after))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("QUEUED")
        .hasMessageContaining("SUCCEEDED");
  }

  @Test
  void requireReachableTransitionAcceptsAValidStateMachineEdge() {
    Run before = Run.queued(RUN_ID, Environment.PUBLIC, Suite.SMOKE, NOW, List.of());
    Run after = before.transitionTo(RunStatus.STARTING, NOW.plusSeconds(1));

    assertThatCode(() -> RunEventValidation.requireReachableTransition(before, after))
        .doesNotThrowAnyException();
  }

  @Test
  void requireReachableTransitionRejectsAChangedStartedAt() {
    Run queued = Run.queued(RUN_ID, Environment.PUBLIC, Suite.SMOKE, NOW, List.of());
    Run before = queued.transitionTo(RunStatus.STARTING, NOW.plusSeconds(1));
    Run running = before.transitionTo(RunStatus.RUNNING, NOW.plusSeconds(2));
    // Reuses running's startedAt but shifts it slightly on the terminal transition - nothing about
    // identity or the event/status pairing catches this, and it stays ordered requestedAt <=
    // startedAt <= finishedAt, so Run's own compact constructor has no reason to reject it either.
    Run after =
        new Run(
            running.runId(),
            running.environment(),
            running.suite(),
            RunStatus.SUCCEEDED,
            running.requestedAt(),
            running.startedAt().minusMillis(1),
            NOW.plusSeconds(3),
            0,
            null,
            running.selectedTests());

    assertThatThrownBy(() -> RunEventValidation.requireReachableTransition(running, after))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("startedAt");
  }

  @Test
  void terminalRejectsAMismatchedOutcome() {
    RunnerEvent wrongOutcome = RunnerEvent.runFinished(RUN_ID, 3L, NOW, RunOutcome.FAILED, "boom");

    assertThatThrownBy(
            () ->
                RunEventValidation.requireLifecycleEventMatches(wrongOutcome, RunStatus.SUCCEEDED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outcome");
  }
}
