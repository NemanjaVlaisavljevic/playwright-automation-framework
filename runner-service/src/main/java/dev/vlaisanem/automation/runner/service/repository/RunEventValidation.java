package dev.vlaisanem.automation.runner.service.repository;

import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunOutcome;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStateMachine;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import java.util.Objects;

/**
 * Shared between {@code JdbcRunStore} and its test double {@code FakeRunLifecycleStore} so the fake
 * can never drift from the real validation. Guards against an event factory returning a mismatched
 * {@code runId}/{@code sequence}, or an event type/outcome that doesn't match the lifecycle
 * transition being recorded.
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
   * Must be called unconditionally by every {@code transitionIfNonTerminal} implementation, even
   * for a {@code null} event, so a caller can never silently commit {@code RUNNING}/a terminal
   * status with no event, or {@code STARTING} with an unexpected one attached. {@code STARTING}
   * requires {@code event == null}; {@code RUNNING} requires {@code RUN_STARTED}; any terminal
   * status requires a matching-outcome {@code RUN_FINISHED}. {@code QUEUED} uses {@link
   * #requireQueuedEvent} instead.
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
   * A {@code transitionIfNonTerminal} caller's {@code UnaryOperator<Run>} must only change
   * status/timing/result, never {@code runId}/{@code environment}/{@code suite}/{@code
   * requestedAt}/{@code selectedTests} - nothing in {@link Run#transitionTo} stops a hand-rolled
   * operator from constructing an arbitrary {@link Run} instead, so this guards against it.
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
   * Re-validates the transition independently of how {@code updated} was produced - a hand-rolled
   * {@code UnaryOperator<Run>} could bypass {@link Run#transitionTo} (and {@link RunStateMachine})
   * entirely, e.g. jumping straight from {@code QUEUED} to {@code SUCCEEDED}. Also requires an
   * already-set {@code startedAt} to stay unchanged, since no single-snapshot validation can catch
   * that on its own.
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
