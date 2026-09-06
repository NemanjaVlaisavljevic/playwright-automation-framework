package dev.vlaisanem.automation.runner.service.repository;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;

/**
 * D2.3 - the store abstraction {@code RunEventBroker}/{@code RunLifecycleCoordinator}/{@code
 * RunService} depend on, rather than the concrete {@code JdbcRunStore} directly - introduced at
 * cutover time (the original D2.2 plan's own "introduce repository/store interfaces" bullet,
 * deliberately deferred until the real callers existed to shape it correctly) so their own unit
 * tests can substitute a fast, in-memory fake instead of standing up a real Testcontainers Postgres
 * for every test that merely exercises orchestration, not the database protocol itself. {@code
 * JdbcRunStore} is the one production implementation; {@code FakeRunLifecycleStore} (test-only)
 * mirrors its exact validation/locking semantics for everything else.
 */
public interface RunLifecycleStore {

  /**
   * Durably accepts a new run and emits its {@code RUN_QUEUED} - always the first event, always
   * sequence 1.
   */
  CommittedRunChange queue(
      String runId,
      Environment environment,
      Suite suite,
      Instant requestedAt,
      List<SelectedTestSnapshot> selectedTests,
      LongFunction<RunnerEvent> queuedEventFactory);

  /**
   * Atomically transitions {@code runId}, optionally allocating and inserting one event, unless the
   * run is already terminal (a benign lost race - returns empty, writes nothing).
   *
   * @param eventFactory builds the event for the allocated sequence, or {@code null} to transition
   *     with no event at all.
   * @throws NoSuchElementException if no run exists for {@code runId}.
   */
  Optional<CommittedRunChange> transitionIfNonTerminal(
      String runId, UnaryOperator<Run> transition, LongFunction<RunnerEvent> eventFactory);

  /**
   * Appends exactly one {@code TEST_*}/{@code STEP_*} event without touching the run's own status,
   * timestamps, or lifecycle version. Returns empty, appending nothing, once the run is already
   * terminal.
   *
   * @throws NoSuchElementException if no run exists for {@code runId}.
   */
  Optional<RunnerEvent> appendEventIfNonTerminal(
      String runId, LongFunction<RunnerEvent> eventFactory);

  Optional<Run> findById(String runId);

  List<Run> findAll();

  /**
   * Every event recorded for {@code runId} with a sequence strictly greater than {@code
   * afterSequence}, in order. Empty if the run has no recorded events at all, or nothing new has
   * been recorded since {@code afterSequence}. Pass {@code 0} to read the full history.
   */
  List<RunnerEvent> readEventsAfter(String runId, long afterSequence);

  /**
   * The most recently recorded event for {@code runId}, or empty if none has been recorded at all.
   */
  Optional<RunnerEvent> latestEvent(String runId);
}
