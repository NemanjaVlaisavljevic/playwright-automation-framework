package dev.vlaisanem.automation.runner.service.orchestration;

import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.exception.RunnerRecoveringException;
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
 * D2.5 (docs/DEPLOYMENT_ARCHITECTURE.md's "Restart behavior" section) - the one-time startup
 * recovery pass: any run still {@code QUEUED}/{@code STARTING}/{@code RUNNING} when this JVM starts
 * was necessarily left that way by a previous instance that is now gone (this JVM's own in-memory
 * {@code activeRuns}/executor/process tracking is always empty on a fresh start) - its external
 * Gradle/Playwright process, if the OS still has it at all, can never be reattached to, so the only
 * honest thing to record is {@code ERROR}, via {@link RunLifecycleCoordinator#finishIfLive} (the
 * same one-transaction status+event commit every other terminal transition already uses - no
 * special "recovery" write path exists, or is needed).
 *
 * <p>{@code ApplicationRunner} - runs once, synchronously, after the application context has fully
 * refreshed. That is <em>after</em> Tomcat's own embedded web server has already started listening
 * (Spring Boot's own startup ordering, not something an {@code ApplicationRunner} can change) - so
 * this class does not, and cannot, keep the socket itself closed during recovery. What actually
 * closes the "traffic could arrive before recovery finishes" window described in the architecture
 * doc is {@link #requireRecoveryComplete()}: {@code RunService#submit}, {@code RunService#cancel},
 * and {@code RunEventStreamController}'s SSE subscribe endpoint each call it before doing anything
 * else, so a request that lands in that brief window is rejected with a {@code 503} ({@link
 * RunnerRecoveringException}) rather than ever observing (or racing) a run recovery is still
 * rewriting.
 *
 * <p><strong>Idempotent under a repeated/aborted-mid-recovery restart</strong>, with no extra code
 * needed for it: {@link RunLifecycleStore#findNonTerminal} only ever returns a run already
 * recovered to {@code ERROR} on a previous startup while it is still non-terminal - once recovered
 * it stops matching that query entirely - so a second restart's pass finds nothing left to do for
 * it, never a second {@code RUN_FINISHED}.
 *
 * <p><strong>Fail-closed, not fail-open, on a recovery failure</strong> (review finding: the
 * original version logged and swallowed every failure - both one run's own {@code finishIfLive}
 * failing, and the initial run-loading call itself failing - then unconditionally marked recovery
 * complete regardless, which let the service accept new run submissions, cancellations, and SSE
 * subscriptions while a stale non-terminal run sat un-reconciled: exactly the state the recovery
 * gate exists to prevent). This class now:
 *
 * <ul>
 *   <li>still attempts every run in the pass even after one fails, collecting failures rather than
 *       stopping early - one bad row must not prevent every other run from being recovered;
 *   <li>never sets {@link #recoveryComplete} if any run failed to recover, or if loading the
 *       non-terminal set itself failed;
 *   <li>throws out of {@link #run(ApplicationArguments)} in either case. An {@code
 *       ApplicationRunner} throwing fails the whole application's startup (a deliberate choice this
 *       time, not an accepted side effect) - the container orchestrator's own restart policy (see
 *       {@code deploy/docker-compose.yml}'s {@code restart: unless-stopped}) then restarts the
 *       process, which retries the pass from scratch. Every run that did successfully recover
 *       before the failure is already durably {@code ERROR} (its own transaction already
 *       committed), so the retry only ever has the genuinely-still-failing run(s) left to attempt -
 *       it never redoes work that already succeeded;
 *   <li>attaches each individual run's own original exception to the thrown aggregate as a
 *       suppressed exception ({@link RecoveryFailure}), rather than leaving it recoverable only
 *       from the log line above - an observability tool that captures the thrown exception itself
 *       still gets the complete causal chain for every run that failed, not just an aggregate
 *       message naming their ids.
 * </ul>
 *
 * A service that instead stayed up forever with the gate permanently closed would have no automated
 * path back to serving traffic at all; failing loudly and letting the orchestrator restart is the
 * simpler, more honest alternative, and surfaces as a visible container restart-count/log signal
 * rather than a silently-stuck 503.
 */
@Component
public class RunRecoveryService implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(RunRecoveryService.class);
  private static final String RECOVERY_DETAIL =
      "runner-service restarted before this run reached a terminal status; its external process,"
          + " if any, could not be reattached to and is presumed gone.";

  private final RunLifecycleStore store;
  private final RunLifecycleCoordinator lifecycle;
  private final AtomicBoolean recoveryComplete = new AtomicBoolean(false);

  public RunRecoveryService(RunLifecycleStore store, RunLifecycleCoordinator lifecycle) {
    this.store = store;
    this.lifecycle = lifecycle;
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
      // Each run's own original failure is attached as suppressed rather than only left behind in
      // the log above, so observability tooling that captures this exception (not just log lines)
      // still gets the complete causal chain for every failed run, not just the aggregate message.
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

  /** Test-only visibility - package-private, not part of the public contract. */
  boolean isRecoveryComplete() {
    return recoveryComplete.get();
  }
}
