package dev.vlaisanem.automation.runner.service.repository;

import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunOutcome;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStateMachine;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import java.util.Objects;

/**
 * Shared between {@code JdbcRunStore} and its test double {@code FakeRunLifecycleStore} -
 * deliberately factored out rather than duplicated, so the fake can never silently drift from the
 * real validation it exists to stand in for. See {@code RunLifecycleStore}'s own Javadoc for why
 * this validation exists at all: an event factory that returns a mismatched {@code runId}/{@code
 * sequence}, or an event type/outcome that does not match the lifecycle transition actually being
 * recorded, would otherwise silently corrupt the row/event correlation.
 */
public final class RunEventValidation {

  private RunEventValidation() {}

  /**
   * Beyond {@code runId}/{@code sequence}, requires the event to genuinely be {@code RUN_QUEUED}.
   */
  public static void requireQueuedEvent(RunnerEvent event, String runId) {
    requireMatchingEvent(event, runId, 1L);
    if (event.type() != EventType.RUN_QUEUED) {
      throw new IllegalArgumentException(
          "queue() requires a RUN_QUEUED event, was " + event.type());
    }
  }

  public static void requireMatchingEvent(RunnerEvent event, String runId, long expectedSequence) {
    Objects.requireNonNull(event, "eventFactory must not return null");
    if (!event.runId().equals(runId)) {
      throw new IllegalArgumentException(
          "Event runId '"
              + event.runId()
              + "' does not match the run this event is being written to: "
              + runId);
    }
    if (event.sequence() != expectedSequence) {
      throw new IllegalArgumentException(
          "Event sequence "
              + event.sequence()
              + " does not match the sequence allocated for it: "
              + expectedSequence);
    }
  }

  /**
   * Must be called unconditionally by every {@code transitionIfNonTerminal} implementation - for
   * every status, including a {@code null} {@code event} (an absent event factory), not only when
   * one happens to be present. A prior version only ran this check inside an {@code if
   * (eventFactory != null)} branch, which meant a caller could commit {@code RUNNING}/a terminal
   * status with no event at all (silently losing an event the replay protocol requires), or commit
   * {@code STARTING} with an arbitrary event attached (one nothing downstream expects or reads).
   * Every status this method is ever called for requires an exact match: {@code STARTING} requires
   * {@code event == null} (see {@code RunLifecycleCoordinator#markStarting}, which always passes a
   * {@code null} {@code eventFactory} for exactly this reason); {@code RUNNING} requires a non-null
   * {@code RUN_STARTED} event; any terminal status requires a non-null {@code RUN_FINISHED} event
   * whose own {@code runOutcome} matches that status. {@code QUEUED} is not a valid transition
   * target here at all - {@code queue()} has its own dedicated {@link #requireQueuedEvent}.
   */
  public static void requireLifecycleEventMatches(RunnerEvent event, RunStatus newStatus) {
    switch (newStatus) {
      case STARTING -> requireNoEvent(event, newStatus);
      case RUNNING -> requireEventOfType(event, newStatus, EventType.RUN_STARTED);
      case SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, ERROR -> requireTerminalEvent(event, newStatus);
      case QUEUED ->
          throw new IllegalArgumentException(
              "requireLifecycleEventMatches is not for QUEUED transitions; use requireQueuedEvent"
                  + " via queue()");
    }
  }

  private static void requireNoEvent(RunnerEvent event, RunStatus newStatus) {
    if (event != null) {
      throw new IllegalArgumentException(
          "Transitioning to " + newStatus + " must not carry an event, got " + event.type());
    }
  }

  private static void requireEventOfType(
      RunnerEvent event, RunStatus newStatus, EventType expectedType) {
    if (event == null || event.type() != expectedType) {
      throw new IllegalArgumentException(
          "Transitioning to "
              + newStatus
              + " requires a "
              + expectedType
              + " event, was "
              + (event == null ? "null" : event.type()));
    }
  }

  private static void requireTerminalEvent(RunnerEvent event, RunStatus newStatus) {
    requireEventOfType(event, newStatus, EventType.RUN_FINISHED);
    RunOutcome expectedOutcome = outcomeFor(newStatus);
    if (event.runOutcome() != expectedOutcome) {
      throw new IllegalArgumentException(
          "RUN_FINISHED outcome "
              + event.runOutcome()
              + " does not match the new status "
              + newStatus
              + " (expected "
              + expectedOutcome
              + ")");
    }
  }

  /**
   * A {@code transitionIfNonTerminal} caller's {@code UnaryOperator<Run>} is trusted to return a
   * status/timing/result change, never a different run - it must not reassign {@code runId}, {@code
   * environment}, {@code suite}, {@code requestedAt}, or {@code selectedTests}. Nothing in {@link
   * Run#transitionTo} enforces this (it copies those fields forward faithfully, but a hand-rolled
   * {@code UnaryOperator<Run>} is free to ignore it and construct an arbitrary {@link Run}
   * instead), so without this check a buggy transition function could silently rewrite a run's own
   * identity out from under the row lock protecting it.
   */
  public static void requireSameIdentity(Run before, Run after) {
    Objects.requireNonNull(after, "transition must not return null");
    if (!before.runId().equals(after.runId())
        || before.environment() != after.environment()
        || before.suite() != after.suite()
        || !before.requestedAt().equals(after.requestedAt())
        || !before.selectedTests().equals(after.selectedTests())) {
      throw new IllegalArgumentException(
          "transition must not change runId/environment/suite/requestedAt/selectedTests: "
              + before
              + " -> "
              + after);
    }
  }

  /**
   * A {@code transitionIfNonTerminal} caller's {@code UnaryOperator<Run>} is trusted to produce
   * {@code updated} via {@link Run#transitionTo}, which itself calls {@link
   * RunStateMachine#requireTransition} - but nothing stops a hand-rolled operator from instead
   * constructing a {@code new Run(...)} directly with an arbitrary status, bypassing {@code
   * transitionTo} (and therefore {@link RunStateMachine}) entirely, e.g. jumping straight from
   * {@code QUEUED} to {@code SUCCEEDED}. Re-validates the transition independently of however
   * {@code updated} was actually produced - the store must never trust a caller-supplied operator
   * to have gone through the state machine just because {@code Run.transitionTo} usually would
   * have. Also requires an already-set {@code startedAt} to stay exactly as it was: once written
   * (at the {@code RUNNING} transition), nothing later - including a run's own terminal transition
   * - may change it, which {@link Run}'s own compact constructor has no way to enforce on its own
   * (it only validates one snapshot in isolation, never against what a specific transition started
   * from).
   */
  public static void requireReachableTransition(Run before, Run after) {
    Objects.requireNonNull(after, "transition must not return null");
    RunStateMachine.requireTransition(before.status(), after.status());
    if (before.startedAt() != null && !before.startedAt().equals(after.startedAt())) {
      throw new IllegalArgumentException(
          "transition must not change an already-set startedAt: "
              + before.startedAt()
              + " -> "
              + after.startedAt());
    }
  }

  /** {@code appendEventIfNonTerminal} is for {@code TEST_*}/{@code STEP_*} events only. */
  public static void requireAppendOnlyEventType(RunnerEvent event) {
    if (!(event.type().isTestLevel() || event.type().isStepLevel())) {
      throw new IllegalArgumentException(
          "appendEventIfNonTerminal is for TEST_*/STEP_* events only, was "
              + event.type()
              + " - use queue()/transitionIfNonTerminal() for RUN_* lifecycle events");
    }
  }

  public static RunOutcome outcomeFor(RunStatus status) {
    return switch (status) {
      case SUCCEEDED -> RunOutcome.SUCCEEDED;
      case FAILED -> RunOutcome.FAILED;
      case CANCELLED -> RunOutcome.CANCELLED;
      case TIMED_OUT -> RunOutcome.TIMED_OUT;
      case ERROR -> RunOutcome.ERROR;
      case QUEUED, STARTING, RUNNING ->
          throw new IllegalArgumentException("Not a terminal status: " + status);
    };
  }
}
