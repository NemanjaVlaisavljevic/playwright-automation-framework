package dev.vlaisanem.automation.runner.service.orchestration;

import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.exception.RunnerRecoveringException;
import dev.vlaisanem.automation.runner.service.metrics.RunnerMetrics;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * One-time startup recovery pass (docs/DEPLOYMENT_ARCHITECTURE.md "Restart behavior"): any run
 * still {@code QUEUED}/{@code STARTING}/{@code RUNNING} when this JVM starts belonged to a
 * previous, now-gone instance, so it is recorded {@code ERROR} via {@link
 * RunLifecycleCoordinator#finishIfLive}.
 *
 * <p>Runs after Tomcat is already listening, so {@link #requireRecoveryComplete()} - called by
 * submit/cancel/SSE-subscribe before anything else - is what actually closes the
 * traffic-before-recovery window, rejecting with a 503 ({@link RunnerRecoveringException}).
 * Idempotent across repeated restarts: an already-recovered run no longer matches {@link
 * RunLifecycleStore#findNonTerminal}.
 *
 * <p>Fails closed: every run in the pass is attempted even after one fails, but {@link
 * #recoveryComplete} is set only if all succeeded; otherwise this throws so the orchestrator
 * restarts the process and retries just the still-failing run(s) (already-recovered ones are
 * terminal and are not re-attempted).
 */
@Component
public class RunRecoveryService implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(RunRecoveryService.class);
  private static final String RECOVERY_DETAIL =
      "runner-service restarted before this run reached a terminal status; its external process,"
          + " if any, could not be reattached to and is presumed gone.";

  private final RunLifecycleStore store;
  private final RunLifecycleCoordinator lifecycle;
  private final RunnerMetrics metrics;
  private final AtomicBoolean recoveryComplete = new AtomicBoolean(false);

  public RunRecoveryService(
      RunLifecycleStore store, RunLifecycleCoordinator lifecycle, RunnerMetrics metrics) {
    this.store = store;
    this.lifecycle = lifecycle;
    this.metrics = metrics;
  }

  @Override
  public void run(ApplicationArguments args) {
    List<Run> nonTerminalRuns;
    try {
      nonTerminalRuns = store.findNonTerminal();
    } catch (RuntimeException loadFailure) {
      throw new IllegalStateException(
          "Startup run-recovery pass could not load the non-terminal runs to recover - refusing to"
              + " accept traffic; the process will exit so the container orchestrator restarts it"
              + " and retries the whole pass.",
          loadFailure);
    }
    if (nonTerminalRuns == null) {
      throw new IllegalStateException(
          "Startup run-recovery pass failed: RunLifecycleStore#findNonTerminal returned null.");
    }

    List<RecoveryFailure> failures = new ArrayList<>();
    for (Run run : nonTerminalRuns) {
      try {
        lifecycle.finishIfLive(run.runId(), RunStatus.ERROR, null, RECOVERY_DETAIL, Instant.now());
      } catch (RuntimeException recoveryFailure) {
        log.error(
            "Failed to recover run {} (last known status {}) to ERROR on startup",
            run.runId(),
            run.status(),
            recoveryFailure);
        failures.add(new RecoveryFailure(run.runId(), recoveryFailure));
      }
    }

    int recoveredCount = nonTerminalRuns.size() - failures.size();
    if (recoveredCount > 0) {
      log.info("Recovered {} non-terminal run(s) to ERROR on startup", recoveredCount);
      metrics.recordRunsRecovered(recoveredCount);
    }
    if (!failures.isEmpty()) {
      IllegalStateException aggregate =
          new IllegalStateException(
              "Startup run-recovery pass failed to recover run(s) "
                  + failures.stream().map(RecoveryFailure::runId).toList()
                  + " - refusing to accept traffic; the process will exit so the container"
                  + " orchestrator restarts it and retries recovery for the run(s) that failed"
                  + " (every already-recovered run is now terminal and will not be"
                  + " re-attempted).");
      // Attach each run's original failure as suppressed so tooling that captures this exception
      // (not just logs) still gets the full causal chain, not just the aggregate message.
      for (RecoveryFailure failure : failures) {
        aggregate.addSuppressed(failure.cause());
      }
      throw aggregate;
    }
    recoveryComplete.set(true);
  }

  private record RecoveryFailure(String runId, RuntimeException cause) {}

  /**
   * @throws RunnerRecoveringException if the one-time startup recovery pass has not finished
   *     completely (and successfully) yet.
   */
  public void requireRecoveryComplete() {
    if (!recoveryComplete.get()) {
      throw new RunnerRecoveringException();
    }
  }

  /**
   * Non-throwing query, unlike {@link #requireRecoveryComplete()} - used by {@code
   * RecoveryHealthIndicator} to report {@code OUT_OF_SERVICE} rather than throwing.
   */
  public boolean isRecoveryComplete() {
    return recoveryComplete.get();
  }
}
