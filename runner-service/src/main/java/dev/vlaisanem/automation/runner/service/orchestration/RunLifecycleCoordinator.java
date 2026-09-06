package dev.vlaisanem.automation.runner.service.orchestration;

import dev.vlaisanem.automation.runner.contract.RunOutcome;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.artifacts.ArtifactIngestionService;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.events.RunEventBroker;
import dev.vlaisanem.automation.runner.service.repository.RunEventValidation;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The sole place that pairs a lifecycle transition with the canonical event it implies. {@link
 * RunService} still owns everything about <em>how</em> a run executes - the executor, process
 * launch/cancel, the DEGRADED process-isolation gate - none of that moves here; this only owns
 * <em>what gets recorded</em> once a transition actually applies.
 *
 * <p>D2.3 cutover: rewritten against {@link RunEventBroker#queue}/{@link
 * RunEventBroker#transitionIfNonTerminal}, which each commit a run's status change and its
 * canonical event in one real Postgres transaction (see {@code JdbcRunStore}) - not the two
 * separately-fallible collaborators (an in-memory {@code RunRepository} plus a file-backed {@code
 * RunEventAppender}) this class depended on before. That single-transaction guarantee is what makes
 * the pre-cutover version of this class's own "emergency ERROR" fallback (a dedicated {@code
 * journalFailure} flag and {@code recordEmergencyError} path, for the case where the repository
 * transition succeeded but the separate journal append then failed, leaving a run's REST-visible
 * status un-backed by any event) unnecessary now: that split-brain state cannot occur any more - a
 * failure anywhere in the store's own transaction rolls back the whole thing, so this class's own
 * methods simply propagate the failure to {@link RunService}, which already has its own top-level
 * fallback (see {@code RunService#executeRun}'s {@code catch (RuntimeException unexpected)} block,
 * itself calling {@link #finishIfLive} to best-effort record {@code ERROR} - unchanged, and now the
 * only such fallback needed anywhere in this path).
 */
@Component
public class RunLifecycleCoordinator {

  private final RunEventBroker broker;
  private final ArtifactIngestionService artifactIngestionService;

  public RunLifecycleCoordinator(
      RunEventBroker broker, ArtifactIngestionService artifactIngestionService) {
    this.broker = broker;
    this.artifactIngestionService = artifactIngestionService;
  }

  /** Durably accepts a new run and emits its {@code RUN_QUEUED} - always the first event. */
  public Run queue(String runId, Environment environment, Suite suite, Instant now) {
    return queue(runId, environment, suite, now, List.of());
  }

  public Run queue(
      String runId,
      Environment environment,
      Suite suite,
      Instant now,
      List<SelectedTestSnapshot> selectedTests) {
    return broker
        .queue(
            runId,
            environment,
            suite,
            now,
            selectedTests,
            seq -> RunnerEvent.runQueued(runId, seq, now))
        .run();
  }

  /**
   * Transitions to {@code STARTING}. Deliberately emits no event: this state is purely an internal
   * detail of a worker having picked up the run (and possibly having to wait out a DEGRADED runner
   * before it can actually launch a process) and carries nothing a dashboard needs - see {@link
   * dev.vlaisanem.automation.runner.contract.EventType}'s own Javadoc on {@code RUN_STARTED}.
   */
  public boolean markStarting(String runId, Instant now) {
    return broker
        .transitionIfNonTerminal(runId, run -> run.transitionTo(RunStatus.STARTING, now), null)
        .isPresent();
  }

  /**
   * Transitions to {@code RUNNING} and, only if that transition actually applied, emits {@code
   * RUN_STARTED}.
   */
  public boolean markRunning(String runId, Instant now) {
    return broker
        .transitionIfNonTerminal(
            runId,
            run -> run.transitionTo(RunStatus.RUNNING, now),
            seq -> RunnerEvent.runStarted(runId, seq, now))
        .isPresent();
  }

  /**
   * Transitions to a terminal {@code status} and, only if that transition actually applied, emits
   * exactly one {@code RUN_FINISHED}. Returns whether the transition applied, mirroring {@link
   * RunEventBroker#transitionIfNonTerminal}.
   *
   * <p>D2.4 - runs {@link ArtifactIngestionService}'s final drain <em>before</em> the transition
   * below, per docs/DEPLOYMENT_ARCHITECTURE.md's "Artifacts must ingest incrementally" section: a
   * manifest entry for a test that failed without a screenshot capture, or any entry the last
   * incremental {@code TEST_FAILED}/{@code TEST_ABORTED} pass hadn't yet seen, must already be
   * ingested by the time a client observes {@code RUN_FINISHED} and queries this run's artifacts.
   */
  public boolean finishIfLive(
      String runId, RunStatus status, Integer exitCode, String detail, Instant now) {
    if (!status.isTerminal()) {
      // Checked before touching the store at all: rejecting only once inside the RUN_FINISHED-
      // event factory below would have already applied the (nonsensical) transition with no way to
      // undo it.
      throw new IllegalArgumentException("finishIfLive requires a terminal status, was: " + status);
    }
    artifactIngestionService.ingestAvailableEntries(runId, true);
    RunOutcome outcome = RunEventValidation.outcomeFor(status);
    return broker
        .transitionIfNonTerminal(
            runId,
            run -> run.transitionTo(status, now, exitCode, detail),
            seq -> RunnerEvent.runFinished(runId, seq, now, outcome, detail))
        .isPresent();
  }
}
