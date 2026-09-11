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
 * RunService} owns <em>how</em> a run executes; this only owns <em>what gets recorded</em> once a
 * transition actually applies. {@link RunEventBroker#queue}/{@link
 * RunEventBroker#transitionIfNonTerminal} commit a run's status change and its canonical event in
 * one transaction, so each of {@link #queue}/{@link #markRunning}/{@link #finishIfLive} also emits
 * one structured log line from that same committed transition - never for a transition that lost
 * its own concurrency race.
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
   * Durably accepts a new run and emits its {@code RUN_QUEUED} - always the first event. Recorded
   * unconditionally, since a disk-low or {@code DEGRADED} rejection never reaches this method.
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
   * Transitions to {@code STARTING}. Emits no event - a worker picking up the run (and possibly
   * waiting out a DEGRADED runner) is an internal detail, not something a dashboard needs.
   */
  public boolean markStarting(String runId, Instant now) {
    return broker
        .transitionIfNonTerminal(runId, run -> run.transitionTo(RunStatus.STARTING, now), null)
        .isPresent();
  }

  /**
   * Transitions to {@code RUNNING} and, only if that transition actually applied, emits {@code
   * RUN_STARTED} and records the metric - a lost race records nothing here.
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
   * Transitions to a terminal {@code status} and, if applied, emits {@code RUN_FINISHED}. Drains
   * {@link ArtifactIngestionService} first so a client observing {@code RUN_FINISHED} sees a
   * complete artifact manifest. Duration is skipped when {@code startedAt} is {@code null}
   * (cancelled/errored before ever reaching {@code RUNNING}).
   */
  public boolean finishIfLive(
      String runId, RunStatus status, Integer exitCode, String detail, Instant now) {
    if (!status.isTerminal()) {
      // Checked before touching the store - otherwise the (nonsensical) transition would already
      // be applied with no way to undo it.
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
