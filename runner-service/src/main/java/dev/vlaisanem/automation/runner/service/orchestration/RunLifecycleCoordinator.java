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
import dev.vlaisanem.automation.runner.service.metrics.RunnerMetrics;
import dev.vlaisanem.automation.runner.service.repository.CommittedRunChange;
import dev.vlaisanem.automation.runner.service.repository.RunEventValidation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>D4.3.3 - each of {@link #queue}/{@link #markRunning}/{@link #finishIfLive} also emits one
 * structured {@code INFO} log line ({@code Run queued}/{@code Run started}/{@code Run finished})
 * from the same committed transition already used for D4.3.2's metrics - never speculatively, and
 * never for a transition that lost its own concurrency race. This is what guarantees every run
 * produces at least these three known, structured log events (with {@code runId} as a real field,
 * not just embedded in a message), regardless of whether any other collaborator happens to log
 * anything else for it.
 */
@Component
public class RunLifecycleCoordinator {

  private static final Logger log = LoggerFactory.getLogger(RunLifecycleCoordinator.class);

  private final RunEventBroker broker;
  private final ArtifactIngestionService artifactIngestionService;
  private final RunnerMetrics metrics;

  public RunLifecycleCoordinator(
      RunEventBroker broker,
      ArtifactIngestionService artifactIngestionService,
      RunnerMetrics metrics) {
    this.broker = broker;
    this.artifactIngestionService = artifactIngestionService;
    this.metrics = metrics;
  }

  /**
   * Durably accepts a new run and emits its {@code RUN_QUEUED} - always the first event.
   *
   * <p>D4.3.2 - {@code broker.queue} never contends (it is always the very first event for this
   * {@code runId}), so {@code runner.runs.submitted} is recorded unconditionally here, from the
   * real committed run - not at any of {@code RunService}'s own earlier rejection points (a
   * disk-low or {@code DEGRADED} rejection never reaches this method at all, so it correctly never
   * counts as "submitted").
   */
  public Run queue(String runId, Environment environment, Suite suite, Instant now) {
    return queue(runId, environment, suite, now, List.of());
  }

  public Run queue(
      String runId,
      Environment environment,
      Suite suite,
      Instant now,
      List<SelectedTestSnapshot> selectedTests) {
    Run run =
        broker
            .queue(
                runId,
                environment,
                suite,
                now,
                selectedTests,
                seq -> RunnerEvent.runQueued(runId, seq, now))
            .run();
    metrics.recordRunSubmitted(run.suite());
    log.atInfo().addKeyValue("runId", runId).addKeyValue("suite", run.suite()).log("Run queued");
    return run;
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
   *
   * <p>D4.3.2 - {@code runner.runs.started} is recorded from the same {@code Optional} this method
   * already uses to decide whether the transition applied, not from a separately re-derived boolean
   * - a lost race (another caller already moved this run past {@code RUNNING}) correctly records
   * nothing here.
   */
  public boolean markRunning(String runId, Instant now) {
    Optional<CommittedRunChange> committed =
        broker.transitionIfNonTerminal(
            runId,
            run -> run.transitionTo(RunStatus.RUNNING, now),
            seq -> RunnerEvent.runStarted(runId, seq, now));
    committed.ifPresent(
        change -> {
          metrics.recordRunStarted(change.run().suite());
          log.atInfo()
              .addKeyValue("runId", runId)
              .addKeyValue("suite", change.run().suite())
              .log("Run started");
        });
    return committed.isPresent();
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
   *
   * <p>D4.3.2 - {@code runner.runs.finished}/{@code runner.runs.duration} are recorded from the
   * same {@code Optional} this method already uses to decide whether the transition applied, never
   * from a separately re-derived boolean at a caller: {@code
   * RunEventBroker.transitionIfNonTerminal} can lose its own concurrency race and return empty
   * (another caller already finished this run), in which case nothing is recorded here - the caller
   * that actually won already recorded it, or will. Duration is computed from the *committed* run's
   * own {@code startedAt}/{@code finishedAt} (not the raw {@code now} parameter) and is skipped
   * entirely when {@code startedAt} is {@code null} - a real, valid case for a run
   * cancelled/errored before ever reaching {@code RUNNING} (see {@code Run}'s own compact
   * constructor).
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
    Optional<CommittedRunChange> committed =
        broker.transitionIfNonTerminal(
            runId,
            run -> run.transitionTo(status, now, exitCode, detail),
            seq -> RunnerEvent.runFinished(runId, seq, now, outcome, detail));
    committed.ifPresent(
        change -> {
          metrics.recordRunFinished(
              change.run().suite(),
              change.run().status(),
              change.run().startedAt(),
              change.run().finishedAt());
          log.atInfo()
              .addKeyValue("runId", runId)
              .addKeyValue("suite", change.run().suite())
              .addKeyValue("status", change.run().status())
              .log("Run finished");
        });
    return committed.isPresent();
  }
}
