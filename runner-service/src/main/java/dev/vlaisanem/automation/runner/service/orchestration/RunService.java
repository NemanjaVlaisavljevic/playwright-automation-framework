package dev.vlaisanem.automation.runner.service.orchestration;

import dev.vlaisanem.automation.runner.service.catalog.RunAvailabilityPolicy;
import dev.vlaisanem.automation.runner.service.catalog.TestCatalogEntry;
import dev.vlaisanem.automation.runner.service.catalog.TestCatalogService;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService.DiskUsageSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.events.IngestionResult;
import dev.vlaisanem.automation.runner.service.events.ListenerEventIngestor;
import dev.vlaisanem.automation.runner.service.events.ListenerEventIngestorFactory;
import dev.vlaisanem.automation.runner.service.exception.DiskSpaceLowException;
import dev.vlaisanem.automation.runner.service.exception.ProcessTerminationException;
import dev.vlaisanem.automation.runner.service.exception.RunLogNotFoundException;
import dev.vlaisanem.automation.runner.service.exception.RunNotFoundException;
import dev.vlaisanem.automation.runner.service.exception.RunQueueFullException;
import dev.vlaisanem.automation.runner.service.exception.RunnerDegradedException;
import dev.vlaisanem.automation.runner.service.process.ProcessLauncher;
import dev.vlaisanem.automation.runner.service.process.ProcessOutcome;
import dev.vlaisanem.automation.runner.service.process.SuiteCommandFactory;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a run's whole lifecycle: validates the request, queues it behind a single active run
 * (a bounded, single-worker executor - see {@link RunnerProperties#queueCapacity()}), launches the
 * Gradle process, tracks it for cancellation, and records the terminal outcome.
 *
 * <p>{@code cancel()} and the background {@code executeRun()} task run on different threads and can
 * race to finalize the same run (e.g. a run finishes normally the instant before a cancel request
 * arrives, or vice versa). Every terminal transition here goes through {@link
 * RunLifecycleCoordinator}, which tolerates losing that <em>specific</em> race (backed by {@code
 * RunLifecycleStore#transitionIfNonTerminal}) without swallowing every other kind of failure:
 * {@code executeRun}'s single try/catch/finally boundary converts anything else (a genuine bug, a
 * store error, an unexpected exception from any collaborator) into a best-effort terminal {@code
 * ERROR}. The {@code finally} block independently guarantees {@code activeRuns} cleanup; failure of
 * the fallback store transition is logged rather than allowed to hide the original execution error.
 *
 * <p>A {@link ProcessTerminationException} means a process from some run is <em>known</em> to still
 * be alive despite our best effort to kill it. Simply freeing the single-worker slot at that point
 * would let a new run execute concurrently with that survivor, breaking single-run isolation - so
 * the runner instead enters {@link Availability#DEGRADED}, rejecting new submissions, until a
 * background reaper confirms every survivor has actually exited. {@code submit()}'s own
 * availability check only rejects a <em>new</em> submission early and can go stale by the time the
 * worker actually gets to it; the real guarantee lives in the process-lifecycle gate shared by
 * launch and termination. No new process can start while termination is unresolved, and a failed
 * termination registers its degradation incident before releasing that gate. Multiple independent
 * incidents are tracked separately, so recovery from one cannot hide a survivor from another.
 */
@Service
public class RunService {

  private static final Logger log = LoggerFactory.getLogger(RunService.class);

  // Bounds cancel()'s wait for executeRun's process()-publish (see ActiveRun#awaitProcessPublished)
  // - only ever needs to cover a single ingestor-thread startup, so this is a generous, fixed
  // safety margin rather than an operational tuning knob.
  private static final Duration PROCESS_PUBLISH_WAIT_TIMEOUT = Duration.ofSeconds(2);

  private enum Availability {
    AVAILABLE,
    DEGRADED
  }

  private final RunLifecycleStore store;
  private final RunLifecycleCoordinator lifecycle;
  private final RunRecoveryService recoveryService;
  private final DiskUsageService diskUsageService;
  private final ProcessLauncher processLauncher;
  private final ListenerEventIngestorFactory ingestorFactory;
  private final TestCatalogService testCatalogService;
  private final RunAvailabilityPolicy availabilityPolicy;
  private final Path repoRoot;
  private final Path rawEventsDir;
  private final Path logsDir;
  private final Path artifactsRootDir;
  private final Duration timeout;
  private final Duration degradedPollInterval;
  private final Duration ingestionDrainTimeout;
  private final int queueCapacity;
  private final long rawEventMaxBytes;
  private final long artifactMaxBytes;
  private final long runMaxTotalArtifactBytes;
  private final long manifestMaxBytes;
  private final long traceCaptureMinFreeBytes;
  private final ThreadPoolExecutor executor;
  private final ScheduledExecutorService reaperExecutor;
  private final Map<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();
  private final AtomicReference<Availability> availability =
      new AtomicReference<>(Availability.AVAILABLE);
  // Identity semantics are deliberate: two exceptions may describe overlapping process handles,
  // but each failed termination is an independently reaped incident. Access is guarded exclusively
  // by processLifecycleLock.
  private final Set<ProcessTerminationException> degradationIncidents =
      Collections.newSetFromMap(new IdentityHashMap<>());
  // Linearizes process launch against the whole termination attempt, not merely the later
  // AVAILABLE -> DEGRADED state flip. A failed termination is registered while this lock is still
  // held, closing the window in which another worker could otherwise launch before learning that a
  // survivor exists.
  private final Object processLifecycleLock = new Object();

  public RunService(
      RunLifecycleStore store,
      RunLifecycleCoordinator lifecycle,
      RunRecoveryService recoveryService,
      DiskUsageService diskUsageService,
      ProcessLauncher processLauncher,
      ListenerEventIngestorFactory ingestorFactory,
      TestCatalogService testCatalogService,
      RunAvailabilityPolicy availabilityPolicy,
      RunnerProperties properties) {
    this.store = store;
    this.lifecycle = lifecycle;
    this.recoveryService = recoveryService;
    this.diskUsageService = diskUsageService;
    this.processLauncher = processLauncher;
    this.ingestorFactory = ingestorFactory;
    this.testCatalogService = testCatalogService;
    this.availabilityPolicy = availabilityPolicy;
    this.repoRoot = Path.of(properties.repoRoot()).toAbsolutePath().normalize();
    this.rawEventsDir = Path.of(properties.rawEventsDir()).toAbsolutePath().normalize();
    this.logsDir = Path.of(properties.logsDir()).toAbsolutePath().normalize();
    this.artifactsRootDir = Path.of(properties.artifactsDir()).toAbsolutePath().normalize();
    this.timeout = properties.processTimeout();
    this.degradedPollInterval = properties.degradedPollInterval();
    this.ingestionDrainTimeout = properties.ingestionDrainTimeout();
    this.queueCapacity = properties.queueCapacity();
    this.rawEventMaxBytes = properties.rawEventMaxBytes();
    this.artifactMaxBytes = properties.artifactMaxBytes();
    this.runMaxTotalArtifactBytes = properties.runMaxTotalArtifactBytes();
    this.manifestMaxBytes = properties.manifestMaxBytes();
    // D4.2 - derived, not independently configured: the producer's own pre-trace-capture
    // free-space floor must be at least as conservative as "the submit-time guard's own floor,
    // plus room for one more artifact" - never a smaller, independently-drifting number.
    this.traceCaptureMinFreeBytes = properties.diskMinFreeBytes() + properties.artifactMaxBytes();
    this.executor =
        new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity));
    this.reaperExecutor =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "runner-degraded-reaper");
              thread.setDaemon(true);
              return thread;
            });
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
    reaperExecutor.shutdownNow();
  }

  public Run submit(Environment environment, Suite suite) {
    return submit(environment, suite, null);
  }

  public Run submit(Environment environment, Suite suite, List<String> testKeys) {
    recoveryService.requireRecoveryComplete();
    if (availability.get() == Availability.DEGRADED) {
      throw new RunnerDegradedException(degradedSurvivingPids());
    }
    DiskUsageSnapshot submitTimeUsage = diskUsageService.snapshot();
    if (submitTimeUsage.belowThreshold()) {
      throw new DiskSpaceLowException(submitTimeUsage);
    }
    RunRequestValidator.validate(availabilityPolicy, environment, suite);
    List<TestCatalogEntry> catalog =
        suite == Suite.CUSTOM ? testCatalogService.current() : List.of();
    List<SelectedTestSnapshot> selectedTests =
        CustomTestSelectionValidator.validate(suite, testKeys, catalog);
    String runId = UUID.randomUUID().toString();
    Run run = lifecycle.queue(runId, environment, suite, Instant.now(), selectedTests);
    ActiveRun activeRun = new ActiveRun();
    activeRuns.put(runId, activeRun);
    Runnable queuedTask = () -> executeRun(runId, environment, suite, selectedTests, activeRun);
    activeRun.queuedTask().set(queuedTask);

    try {
      executor.execute(queuedTask);
    } catch (RejectedExecutionException exception) {
      activeRun.queuedTask().set(null);
      activeRuns.remove(runId);
      lifecycle.finishIfLive(runId, RunStatus.CANCELLED, null, "Run queue is full", Instant.now());
      throw new RunQueueFullException(queueCapacity);
    }
    return run;
  }

  public Run find(String runId) {
    return store.findById(runId).orElseThrow(() -> new RunNotFoundException(runId));
  }

  public List<Run> findAll() {
    return store.findAll();
  }

  public Path processLog(String runId) {
    find(runId);
    Path logFile = processLogPath(runId);
    if (!Files.isRegularFile(logFile)) {
      throw new RunLogNotFoundException(runId);
    }
    return logFile;
  }

  /**
   * Requests cancellation. For a run whose process has already been launched, this synchronously
   * sends the kill signal but returns whatever status the run happens to have at that instant - the
   * worker thread's {@code executeRun} still has to notice the process died and record the terminal
   * transition, so the returned {@link Run} may still show {@code RUNNING} even though cancellation
   * is already in flight. A caller that needs the confirmed final status should poll {@link #find}.
   * A run that is successfully removed from the executor queue is the one case this returns
   * synchronously as {@code CANCELLED}, since no worker or process remains to acknowledge it.
   */
  public Run cancel(String runId) {
    recoveryService.requireRecoveryComplete();
    Run current = find(runId);
    if (current.status().isTerminal()) {
      return current;
    }
    ActiveRun activeRun = activeRuns.get(runId);
    if (activeRun == null) {
      throw new IllegalStateException("No active tracking found for non-terminal run: " + runId);
    }
    activeRun.cancelRequested().set(true);
    Process process = activeRun.process().get();
    if (process == null) {
      Runnable queuedTask = activeRun.queuedTask().getAndSet(null);
      if (queuedTask != null) {
        // Still sitting in the queue - nothing to kill, just remove it. If executor.remove() loses
        // this race (the worker just claimed the same task), executeRun()'s own cancelRequested
        // check before launching (see its own comment) records CANCELLED momentarily on its own;
        // nothing further for this call to do.
        if (executor.remove(queuedTask)) {
          try {
            lifecycle.finishIfLive(
                runId, RunStatus.CANCELLED, null, "Run was cancelled while queued", Instant.now());
          } finally {
            // The executor no longer owns this task, so active tracking must be released even when
            // the terminal event cannot be persisted and finishIfLive propagates that failure.
            activeRuns.remove(runId, activeRun);
          }
        }
        return find(runId);
      }
      // queuedTask was already null: the worker had already cleared it at executeRun's own start
      // and is actively launching this run - process() publish is only instants away (see
      // executeRun's own publish-ordering comment). Wait briefly so this call can still terminate
      // the process synchronously, rather than silently no-op'ing and leaving termination to the
      // worker's own best-effort post-publish check, which cannot report a result back to this
      // caller - this is exactly the window a real crash-recovery review round found could let a
      // concurrent cancel() lose the race and return a stale RUNNING/STARTING status.
      process = activeRun.awaitProcessPublished(PROCESS_PUBLISH_WAIT_TIMEOUT);
    }
    if (process == null) {
      // Genuinely never launched (e.g. cancelled/errored while waiting on availability, or
      // start() itself failed) - the worker already recorded its own terminal status.
      return find(runId);
    }
    try {
      terminateWithinLifecycleGate(process);
    } catch (ProcessTerminationException exception) {
      log.error("Could not terminate the process tree for cancelled run {}", runId, exception);
      // This run's own worker may be stuck arbitrarily long inside awaitCompletion() on the same
      // (unkillable) process, so its ingestor (if one was ever started) may still be forwarding
      // legitimately-occurred test events. Stopping and draining it here, from this thread,
      // before closing the canonical journal below - rather than leaving that to the worker,
      // which might not get there for a while - is what stops the emergency finalization from
      // racing ahead of, and silently dropping, those events. Idempotent if the worker later
      // drains the same ingestor itself.
      ListenerEventIngestor runIngestor = activeRun.ingestor().get();
      if (runIngestor != null) {
        runIngestor.stopAndAwaitFinished(ingestionDrainTimeout);
      }
      try {
        lifecycle.finishIfLive(
            runId,
            RunStatus.ERROR,
            null,
            "Cancellation failed because the process tree survived termination; PIDs: "
                + exception.survivingPids(),
            Instant.now());
      } finally {
        // This run's own worker is likely still blocked inside awaitCompletion() on this same
        // (unkillable) process and would otherwise not notice for up to the full configured
        // timeout. Cleanup is unconditional even if recording ERROR fails: the journal failure
        // is propagated, but must not strand the only worker. interruptWorkerIfAttached() is
        // synchronized against that worker's own detach, so this can never land on a different
        // run's worker after this one finishes and the pool thread gets reused (see ActiveRun).
        activeRun.interruptWorkerIfAttached();
      }
    }
    return find(runId);
  }

  private void executeRun(
      String runId,
      Environment environment,
      Suite suite,
      List<SelectedTestSnapshot> selectedTests,
      ActiveRun activeRun) {
    Process process = null;
    ListenerEventIngestor ingestor = null;
    activeRun.attachWorker(Thread.currentThread());
    try {
      activeRun.queuedTask().set(null);
      if (activeRun.cancelRequested().get()) {
        // The worker won the race to take this task from the queue before cancel() could remove it;
        // it therefore owns the terminal acknowledgement even though no process was launched.
        lifecycle.finishIfLive(
            runId,
            RunStatus.CANCELLED,
            null,
            "Run was cancelled before process launch",
            Instant.now());
        return;
      }

      if (!lifecycle.markStarting(runId, Instant.now())) {
        return;
      }

      List<String> command =
          SuiteCommandFactory.commandFor(
              environment,
              suite,
              repoRoot,
              runId,
              rawEventsDir,
              artifactsRootDir.resolve(runId).resolve("allure-results"),
              selectedTests);
      process = awaitAvailableThenStart(runId, activeRun, command);
      if (process == null) {
        // Already recorded a terminal status (CANCELLED while waiting, or ERROR from a start()
        // failure) inside the helper - nothing left to do.
        return;
      }

      // Started only once RUN_STARTED is confirmed durable - never before, or a TEST_* event
      // already sitting in the raw file could be forwarded (and canonically sequenced) by the
      // ingestor thread before this thread's own RUN_STARTED append wins the race. The tailer
      // always reads from byte 0, so nothing already written before this point is lost by waiting.
      boolean stillLive = lifecycle.markRunning(runId, Instant.now());
      if (stillLive) {
        ingestor = ingestorFactory.start(runId);
        // Attached before activeRun.process() is published just below - never after - so cancel()
        // on a different thread can never observe this run's process without also being able to
        // find and drain its ingestor. Without that ordering, a cancellation racing in right after
        // publish but before this line could see a live process, fail to terminate it, find no
        // ingestor yet, and close the canonical journal while the ingestor (started moments later)
        // is still forwarding legitimately-occurred test events into it.
        activeRun.ingestor().set(ingestor);
      }
      activeRun.publishProcess(process);
      // Checked immediately after publish - not before it, and only once, not twice. From the
      // instant activeRun.process() becomes visible, cancel() on a different thread can act on it
      // directly; this check is what covers every cancellation that arrived any time earlier
      // (queued before launch, during awaitAvailableThenStart, or during markRunning/ingestor
      // setup above), when the process was not yet visible to cancel() at all. Checking before
      // publish instead - or checking twice, once before and once after - would leave exactly the
      // gap between that earlier check and this publish unmonitored by either side: cancel() could
      // not yet see the process, and this thread would not check again, so a cancellation landing
      // in that gap would only be noticed once awaitCompletion's full timeout elapsed.
      if (activeRun.cancelRequested().get()) {
        terminateWithinLifecycleGate(process);
      }

      ProcessOutcome outcome = processLauncher.awaitCompletion(process, timeout);
      if (!stillLive) {
        // Finalized concurrently (e.g. cancelled) while this thread was launching/awaiting -
        // nothing left to record, and no ingestor was ever started to clean up.
        return;
      }
      // Stopped/drained before this run's own RUN_FINISHED below - so RUN_FINISHED is always the
      // last event in a run's canonical timeline, never preceded by a TEST_* event forwarded after
      // the fact. Safe to call even if cancel() already stopped this same ingestor concurrently
      // (see above) - stopAndAwaitFinished is idempotent once the ingestor has actually finished.
      IngestionResult ingestion = ingestor.stopAndAwaitFinished(ingestionDrainTimeout);

      RunStatus finalStatus = classify(outcome, activeRun.cancelRequested().get());
      String detail = detailFor(outcome, finalStatus);
      boolean interruptedOutcome =
          finalStatus == RunStatus.CANCELLED || finalStatus == RunStatus.TIMED_OUT;

      if (!ingestion.valid()) {
        // A malformed line, a source-sequence gap/duplicate, or a wrong-runId/non-test event means
        // the raw stream's own internal consistency broke down - trusting any classification built
        // from it (including the process's own exit code) is no longer defensible.
        finalStatus = RunStatus.ERROR;
        detail = "Listener event ingestion failed: " + ingestion.detail();
      } else if (!ingestion.sawCompletionMarker()) {
        if (interruptedOutcome) {
          // Expected: the JVM may have been killed before the listener closed its writer. The raw
          // stream up to that point was still fully validated and forwarded above - only note that
          // it may be incomplete, don't discard an otherwise-legitimate CANCELLED/TIMED_OUT result.
          detail =
              (detail == null ? "" : detail + "; ")
                  + "raw event stream is incomplete because the process was interrupted before the"
                  + " listener closed it";
        } else if (finalStatus == RunStatus.SUCCEEDED) {
          // A SUCCEEDED classification (exit 0) is only trustworthy if the listener's own event log
          // agrees the run actually completed - exit 0 with a missing marker means something went
          // wrong that the exit code alone doesn't reveal. A non-zero (FAILED) exit already proves
          // failure on its own and needs no such confirmation.
          finalStatus = RunStatus.ERROR;
          detail =
              "Process exited "
                  + outcome.exitCode()
                  + " but the event log was never marked"
                  + " complete";
        }
      }

      lifecycle.finishIfLive(runId, finalStatus, outcome.exitCode(), detail, Instant.now());
    } catch (RuntimeException unexpected) {
      log.error("Run {} failed unexpectedly, marking it ERROR", runId, unexpected);
      if (ingestor != null) {
        // Best-effort: the run is already being recorded as ERROR regardless of what (if anything)
        // was ingested, but the ingestion thread must not be left running past this run's own
        // lifecycle.
        ingestor.stopAndAwaitFinished(ingestionDrainTimeout);
      }
      Process finalProcess = process;
      if (finalProcess != null) {
        try {
          terminateWithinLifecycleGate(finalProcess);
        } catch (RuntimeException terminationFailure) {
          log.warn(
              "Failed to terminate the process for run {} during error handling",
              runId,
              terminationFailure);
        }
      }
      try {
        lifecycle.finishIfLive(
            runId,
            RunStatus.ERROR,
            null,
            "Unexpected failure: " + unexpected.getMessage(),
            Instant.now());
      } catch (RuntimeException terminalizationFailure) {
        unexpected.addSuppressed(terminalizationFailure);
        log.error("Run {} could not be recorded as ERROR", runId, terminalizationFailure);
      }
    } finally {
      activeRun.detachWorker(Thread.currentThread());
      activeRuns.remove(runId);
    }
  }

  /**
   * Blocks the worker - which has nothing useful to do until this run either launches or is
   * cancelled/interrupted anyway - while the runner is {@link Availability#DEGRADED}, then
   * atomically re-confirms {@link Availability#AVAILABLE} and launches under the same lifecycle
   * gate used by termination. A termination already in progress therefore finishes first; if it
   * fails, its degradation incident is visible before this method can reacquire the gate. The run
   * stays visible as {@code STARTING} for as long as this waits. Returns {@code null} once a
   * terminal status has already been recorded (cancelled while waiting, interrupted, or a {@code
   * start()} failure) - the caller has nothing further to do in that case.
   */
  private Process awaitAvailableThenStart(String runId, ActiveRun activeRun, List<String> command) {
    while (true) {
      while (availability.get() == Availability.DEGRADED) {
        if (activeRun.cancelRequested().get()) {
          lifecycle.finishIfLive(
              runId,
              RunStatus.CANCELLED,
              null,
              "Run was cancelled while waiting for the runner to recover from a degraded state",
              Instant.now());
          return null;
        }
        try {
          Thread.sleep(degradedPollInterval.toMillis());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          lifecycle.finishIfLive(
              runId,
              RunStatus.CANCELLED,
              null,
              "Interrupted while waiting for the runner to recover from a degraded state",
              Instant.now());
          return null;
        }
      }
      synchronized (processLifecycleLock) {
        if (availability.get() != Availability.AVAILABLE) {
          // Lost a race against a fresh degradation between the wait loop above and this check -
          // go back and wait properly rather than launching into a now-unsafe window.
          continue;
        }
        // A submit()-time disk check alone does not protect a run that was already queued: with a
        // bounded queue and a single worker, several requests can each pass that earlier check
        // before the first actually consumes disk. Re-checked here, immediately before the process
        // that would actually consume it starts - unlike DEGRADED above, this never loops/retries:
        // disk pressure isn't something this service resolves on its own schedule, so a queued run
        // that can't safely start is terminalized now, not launched and not waited out.
        DiskUsageSnapshot preLaunchUsage = diskUsageService.snapshot();
        if (preLaunchUsage.belowThreshold()) {
          lifecycle.finishIfLive(
              runId,
              RunStatus.ERROR,
              null,
              "Run was not started: available disk space is below the configured safety threshold"
                  + " ("
                  + preLaunchUsage.usableFreeBytes()
                  + " bytes usable, "
                  + (preLaunchUsage.diskMinFreeBytes() + preLaunchUsage.runMaxDiskBytes())
                  + " bytes required)",
              Instant.now());
          return null;
        }
        try {
          // D4.2 - threaded down from this same RunnerProperties-sourced config so a
          // dashboard-launched run's producer-side limits (enforced in the main framework/
          // runner-listener child process) always agree with the consumer-side limits it's later
          // checked against - a standalone Gradle invocation this service never launched falls
          // back to TestConfig's own defaults instead.
          Map<String, String> environment =
              Map.of(
                  "ARTIFACTS_DIR", reserveArtifactsDirectory(runId).toString(),
                  "ARTIFACT_MAX_BYTES", String.valueOf(artifactMaxBytes),
                  "RUN_MAX_TOTAL_ARTIFACT_BYTES", String.valueOf(runMaxTotalArtifactBytes),
                  "MANIFEST_MAX_BYTES", String.valueOf(manifestMaxBytes),
                  "TRACE_CAPTURE_MIN_FREE_BYTES", String.valueOf(traceCaptureMinFreeBytes),
                  // An environment variable, not a -D flag: it must reach
                  // RunnerEventWriterRegistry inside the forked JUnit test-worker JVM itself, and
                  // (unlike system properties) an env var set here on the spawned build JVM is
                  // inherited automatically by that forked worker - no build.gradle forwarding
                  // needed, unlike -Drunner.allureResultsDir= below.
                  "RUNNER_RAW_EVENT_MAX_BYTES", String.valueOf(rawEventMaxBytes),
                  "ALLURE_ATTACHMENTS_ENABLED", "false",
                  // D4.2 - video recording never goes through ArtifactManifestWriter at all (it is
                  // written directly by Playwright on browser-context close, with no producer-side
                  // cap/manifest-entry/delete-on-reject the way screenshots and traces get), so it
                  // is not a supported artifact type on this execution path - forced off
                  // regardless of the host environment's own RECORD_VIDEO setting, rather than
                  // silently letting an unbounded video file bypass every D4.2 budget.
                  "RECORD_VIDEO", "false");
          return processLauncher.start(command, repoRoot, processLogPath(runId), environment);
        } catch (IOException exception) {
          lifecycle.finishIfLive(
              runId,
              RunStatus.ERROR,
              null,
              "Failed to start process: " + exception.getMessage(),
              Instant.now());
          return null;
        }
      }
    }
  }

  private RunStatus classify(ProcessOutcome outcome, boolean cancelRequested) {
    if (cancelRequested) {
      return RunStatus.CANCELLED;
    }
    return switch (outcome.kind()) {
      case COMPLETED -> outcome.exitCode() == 0 ? RunStatus.SUCCEEDED : RunStatus.FAILED;
      case TIMED_OUT -> RunStatus.TIMED_OUT;
    };
  }

  private String detailFor(ProcessOutcome outcome, RunStatus finalStatus) {
    return switch (finalStatus) {
      case FAILED -> "Gradle exited with code " + outcome.exitCode();
      case TIMED_OUT -> "Process exceeded the configured timeout (" + timeout + ") and was killed";
      case CANCELLED -> "Run was cancelled";
      default -> null;
    };
  }

  /**
   * Every run gets its own isolated subdirectory - never reused, never shared - so screenshots/
   * traces from two different runs (sequential today; concurrent if this runner is ever scaled past
   * its current single-worker executor) can never land in the same directory. {@code Files.exists}
   * followed by a separate creation would leave a check-then-act race between two callers; {@code
   * Files.createDirectory} is atomic - exactly one caller for a given {@code runId} ever succeeds,
   * whether the race is against another run or (once {@code runId} is always a fresh UUID, as it is
   * today) a genuine collision/reuse bug. On a start failure right after this succeeds, the
   * now-empty directory is deliberately left in place - it belongs to that run's own {@code ERROR}
   * outcome and may still gain a manifest/diagnostic entry later, so nothing here ever removes it.
   */
  // Package-private, not private: RunServiceTest exercises the reservation directly (see
  // reservesADistinctArtifactsDirectoryPerRun) - triggering every case (first caller wins a genuine
  // race, not just a UUID collision) through the full async submit() flow isn't deterministic.
  Path reserveArtifactsDirectory(String runId) throws IOException {
    Files.createDirectories(artifactsRootDir);
    Path dir = artifactsRootDir.resolve(runId);
    try {
      return Files.createDirectory(dir);
    } catch (FileAlreadyExistsException exception) {
      throw new IllegalStateException(
          "Artifacts directory already exists for run " + runId + ": " + dir, exception);
    }
  }

  private Path processLogPath(String runId) {
    return logsDir.resolve(runId + ".log");
  }

  /**
   * Terminates under the same gate used by process launch. A failure is registered before the gate
   * is released, so the next run can observe only one of two safe outcomes: termination completed,
   * or the runner is already degraded.
   */
  private void terminateWithinLifecycleGate(Process process) {
    synchronized (processLifecycleLock) {
      try {
        processLauncher.terminate(process);
      } catch (ProcessTerminationException exception) {
        enterDegradedWhileLocked(exception);
        throw exception;
      }
    }
  }

  private void enterDegradedWhileLocked(ProcessTerminationException exception) {
    if (!Thread.holdsLock(processLifecycleLock)) {
      throw new IllegalStateException("Process lifecycle lock must be held while degrading runner");
    }
    if (!degradationIncidents.add(exception)) {
      return;
    }
    availability.set(Availability.DEGRADED);
    log.error(
        "Runner entering/remaining DEGRADED - {} unresolved termination incident(s); newly"
            + " surviving PIDs {}",
        degradationIncidents.size(),
        exception.survivingPids());
    scheduleSurvivorCheck(exception);
  }

  private List<Long> degradedSurvivingPids() {
    synchronized (processLifecycleLock) {
      return degradationIncidents.stream()
          .flatMap(exception -> exception.survivingPids().stream())
          .distinct()
          .sorted()
          .toList();
    }
  }

  private void scheduleSurvivorCheck(ProcessTerminationException exception) {
    reaperExecutor.schedule(
        () -> checkSurvivors(exception), degradedPollInterval.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void checkSurvivors(ProcessTerminationException exception) {
    if (exception.anySurvivorStillAlive()) {
      scheduleSurvivorCheck(exception);
      return;
    }
    boolean recovered;
    int remainingIncidents;
    synchronized (processLifecycleLock) {
      degradationIncidents.remove(exception);
      remainingIncidents = degradationIncidents.size();
      recovered = degradationIncidents.isEmpty();
      if (recovered) {
        availability.set(Availability.AVAILABLE);
      }
    }
    if (recovered) {
      log.info("Runner recovered from DEGRADED state - all surviving processes have exited");
    } else {
      log.info(
          "One termination incident was reaped; runner remains DEGRADED with {} incident(s)",
          remainingIncidents);
    }
  }
}
