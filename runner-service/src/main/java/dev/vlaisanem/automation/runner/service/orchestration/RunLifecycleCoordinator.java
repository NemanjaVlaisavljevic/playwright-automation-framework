package dev.vlaisanem.automation.runner.service.orchestration;

import dev.vlaisanem.automation.runner.contract.RunOutcome;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.events.RunEventAppender;
import dev.vlaisanem.automation.runner.service.exception.RunEventPersistenceException;
import dev.vlaisanem.automation.runner.service.repository.RunRepository;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/**
 * The sole place that pairs a lifecycle transition with the canonical event it implies. {@link
 * RunService} still owns everything about <em>how</em> a run executes - the executor, process
 * launch/cancel, the DEGRADED process-isolation gate - none of that moves here; this only owns
 * <em>what gets recorded</em> once a transition actually applies.
 *
 * <p>Every event-bearing transition is validated, then persists its event through {@link
 * RunRepository#transitionIfNonTerminal(String, UnaryOperator, Consumer)} before the new snapshot
 * is committed. That repository operation's own race tolerance (only one of two racing callers ever
 * sees {@code applied == true}) guarantees a run's canonical timeline never emits more than one
 * {@code RUN_FINISHED}, and never emits {@code RUN_STARTED} for a lifecycle transition lost to
 * concurrent cancellation/error. A repository-level failure (e.g. a genuinely invalid transition)
 * still propagates uncaught; only the specific "already terminal" race is tolerated here.
 *
 * <p>Event persistence is part of the repository's per-run pre-commit boundary. If it fails, the
 * target snapshot is never exposed without its event. An already-accepted run is instead moved to
 * an emergency repository-only {@code ERROR}: no terminal event can honestly be fabricated once the
 * journal is untrustworthy, and the missing completion marker remains the durable evidence of that
 * fact. The journal dependency then stays unavailable until restart, so later submissions fail
 * closed rather than producing timelines that cannot be replayed.
 */
@Component
public class RunLifecycleCoordinator {

  private final RunRepository repository;
  private final RunEventAppender eventAppender;
  private final AtomicReference<RuntimeException> journalFailure = new AtomicReference<>();

  public RunLifecycleCoordinator(RunRepository repository, RunEventAppender eventAppender) {
    this.repository = repository;
    this.eventAppender = eventAppender;
  }

  /** Durably accepts a new run and emits its {@code RUN_QUEUED} - always the first event. */
  public Run queue(String runId, Environment environment, Suite suite, Instant now) {
    Run run = Run.queued(runId, environment, suite, now);
    return repository.save(
        run, ignored -> appendEvent(runId, seq -> RunnerEvent.runQueued(runId, seq, now)));
  }

  /**
   * Transitions to {@code STARTING}. Deliberately emits no event: this state is purely an internal
   * detail of a worker having picked up the run (and possibly having to wait out a DEGRADED runner
   * before it can actually launch a process) and carries nothing a dashboard needs - see {@link
   * dev.vlaisanem.automation.runner.contract.EventType}'s own Javadoc on {@code RUN_STARTED}.
   */
  public boolean markStarting(String runId, Instant now) {
    return transitionWithJournalGuard(
        runId,
        run -> run.transitionTo(RunStatus.STARTING, now),
        ignored -> requireJournalAvailable(runId),
        now);
  }

  /**
   * Transitions to {@code RUNNING} and, only if that transition actually applied, emits {@code
   * RUN_STARTED}.
   */
  public boolean markRunning(String runId, Instant now) {
    return transitionWithJournalGuard(
        runId,
        run -> run.transitionTo(RunStatus.RUNNING, now),
        ignored -> appendEvent(runId, seq -> RunnerEvent.runStarted(runId, seq, now)),
        now);
  }

  /**
   * Transitions to a terminal {@code status} and, only if that transition actually applied, emits
   * exactly one {@code RUN_FINISHED}. Returns whether the transition applied, mirroring {@link
   * RunRepository#transitionIfNonTerminal}.
   */
  public boolean finishIfLive(
      String runId, RunStatus status, Integer exitCode, String detail, Instant now) {
    if (!status.isTerminal()) {
      // Checked before touching the repository at all: rejecting only once inside the
      // RUN_FINISHED-event factory below would have already applied the (nonsensical) transition
      // with no way to undo it.
      throw new IllegalArgumentException("finishIfLive requires a terminal status, was: " + status);
    }
    return transitionWithJournalGuard(
        runId,
        run -> run.transitionTo(status, now, exitCode, detail),
        ignored ->
            appendEvent(
                runId, seq -> RunnerEvent.runFinished(runId, seq, now, outcomeFor(status), detail)),
        now);
  }

  private boolean transitionWithJournalGuard(
      String runId, UnaryOperator<Run> transition, Consumer<Run> beforeCommit, Instant now) {
    try {
      return repository.transitionIfNonTerminal(runId, transition, beforeCommit);
    } catch (RunEventPersistenceException persistenceFailure) {
      recordEmergencyError(runId, now, persistenceFailure);
      throw persistenceFailure;
    }
  }

  private RunnerEvent appendEvent(String runId, LongFunction<RunnerEvent> eventFactory) {
    requireJournalAvailable(runId);
    try {
      return eventAppender.append(runId, eventFactory);
    } catch (RuntimeException failure) {
      journalFailure.compareAndSet(null, failure);
      throw new RunEventPersistenceException(runId, failure);
    }
  }

  private void requireJournalAvailable(String runId) {
    RuntimeException failure = journalFailure.get();
    if (failure != null) {
      throw new RunEventPersistenceException(runId, failure);
    }
  }

  private void recordEmergencyError(
      String runId, Instant now, RunEventPersistenceException persistenceFailure) {
    Throwable rootCause = persistenceFailure.getCause();
    String causeDetail =
        rootCause == null ? persistenceFailure.getMessage() : rootCause.getMessage();
    String detail =
        "Canonical event journal failed; event timeline is incomplete"
            + (causeDetail == null || causeDetail.isBlank() ? "" : ": " + causeDetail);
    try {
      repository.transitionIfNonTerminal(
          runId, run -> run.transitionTo(RunStatus.ERROR, now, null, detail));
    } catch (RuntimeException emergencyFailure) {
      persistenceFailure.addSuppressed(emergencyFailure);
    }
  }

  private RunOutcome outcomeFor(RunStatus status) {
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
