package dev.vlaisanem.automation.runner.service.repository;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;

/**
 * The store abstraction {@code RunEventBroker}/{@code RunLifecycleCoordinator}/{@code RunService}
 * depend on, rather than the concrete {@code JdbcRunStore} directly, so their unit tests can
 * substitute a fast in-memory fake instead of a real Testcontainers Postgres. {@code JdbcRunStore}
 * is the one production implementation; {@code FakeRunLifecycleStore} (test-only) mirrors its exact
 * validation/locking semantics.
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

  /**
   * Empty both for a runId that never existed and for one with {@code cleanup_started_at} set - a
   * tombstoned run is logically gone from every public read path immediately, well before its
   * files/row are actually removed. {@code RetentionService} is the only caller that can still see
   * one, via {@link #findPendingCleanup}.
   */
  Optional<Run> findById(String runId);

  /** Never includes a tombstoned run - see {@link #findById}'s own Javadoc for why. */
  List<Run> findAll();

  /**
   * Every run whose status is {@code QUEUED}/{@code STARTING}/{@code RUNNING} right now - the exact
   * set {@code RunRecoveryService}'s startup pass needs, without paying for every already-terminal
   * run's own history the way {@link #findAll} does. Never {@code null}; empty once nothing is left
   * to recover.
   */
  List<Run> findNonTerminal();

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

  // --- Retention - internal-only, never called outside RetentionService. Every method below
  // still sees a tombstoned/pending-purge run, unlike findById/findAll. ---

  /**
   * Terminal runs, {@code cleanup_started_at IS NULL}, ranked newest-first ({@code finished_at
   * DESC, requested_at DESC, run_id DESC} to break ties). A run qualifies once its rank exceeds
   * {@code maxCount} <strong>or</strong> {@code finished_at} is older than {@code
   * now.minus(maxAge)} - an either-bound rule. An already-tombstoned run never occupies a rank
   * slot.
   */
  List<String> findEligibleForCleanup(Instant now, Duration maxAge, int maxCount);

  /**
   * Every run with {@code cleanup_started_at IS NOT NULL} - resume candidates for a cleanup
   * interrupted by a crash. Disjoint from {@link #findEligibleForCleanup} by construction.
   */
  List<String> findPendingCleanup();

  /**
   * Atomically claims {@code runId} for full cleanup - {@code UPDATE ... WHERE cleanup_started_at
   * IS NULL AND status IN (terminal)}. Returns whether this call actually set it; {@code false}
   * means another concurrent sweep already claimed it (or it is no longer eligible at all) - the
   * caller must not touch that run's files in that case. This is the sole concurrency guard against
   * two overlapping sweeps (a scheduled tick racing a manual on-demand trigger).
   */
  boolean claimForCleanup(String runId);

  /**
   * Deletes the {@code runs} row for {@code runId} - cascades to {@code run_selected_tests}/{@code
   * run_events}/{@code artifacts}. Must only ever be called after every on-disk file for that run
   * is confirmed gone; calling it before that would leave a dangling on-disk file with no DB record
   * of it ever existing.
   */
  void deleteRun(String runId);

  /**
   * Terminal runs, {@code artifacts_purge_started_at IS NULL}, whose {@code finished_at} is older
   * than {@code now.minus(maxAge)} - measured from the run's completion time, not individual
   * artifact rows, so every artifact belonging to a run ages out together.
   */
  List<String> findEligibleForArtifactPurge(Instant now, Duration maxAge);

  /**
   * Every run with {@code artifacts_purge_started_at IS NOT NULL AND artifacts_purged_at IS NULL} -
   * resume candidates for a purge interrupted by a crash.
   */
  List<String> findPendingArtifactPurge();

  /**
   * Atomically claims {@code runId} for artifact purge - same shape and same concurrency guarantee
   * as {@link #claimForCleanup}, scoped to {@code artifacts_purge_started_at}.
   */
  boolean claimForArtifactPurge(String runId);
}
