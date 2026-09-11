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
import dev.vlaisanem.automation.runner.service.filesystem.RunFilePaths;
import dev.vlaisanem.automation.runner.service.logging.MdcScope;
import dev.vlaisanem.automation.runner.service.metrics.RunnerMetrics;
import dev.vlaisanem.automation.runner.service.process.ProcessLauncher;
import dev.vlaisanem.automation.runner.service.process.ProcessOutcome;
import dev.vlaisanem.automation.runner.service.process.SuiteCommandFactory;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
 * (a bounded, single-worker executor), launches the Gradle process, tracks it for cancellation, and
 * records the terminal outcome.
 *
 * <p>{@code cancel()} and the background {@code executeRun()} task run on different threads and can
 * race to finalize the same run. Every terminal transition goes through {@link
 * RunLifecycleCoordinator}, which tolerates losing that race; {@code executeRun}'s own
 * try/catch/finally converts any other failure into a best-effort terminal {@code ERROR} and always
 * cleans up {@code activeRuns}.
 *
 * <p>A {@link ProcessTerminationException} means a process is known to still be alive despite our
 * best effort to kill it. The runner enters {@link Availability#DEGRADED} and rejects new
 * submissions until a background reaper confirms every survivor has exited - enforced by the shared
 * process-lifecycle gate between launch and termination, not just {@code submit()}'s (staleness-
 * prone) early check. Multiple incidents are tracked separately, so one recovering can't hide
 * another.
 */
@Service
public class RunService {

  private static final Logger log = LoggerFactory.getLogger(RunService.class);

  // Bounds cancel()'s wait for executeRun's process()-publish - a fixed safety margin, not a
  // tuning knob, since it only ever needs to cover one ingestor-thread startup.
  private static final Duration PROCESS_PUBLISH_WAIT_TIMEOUT = Duration.ofSeconds(2);

  private enum Availability {
    AVAILABLE,
    DEGRADED
  }

  private final RunLifecycleStore store;
  private final RunLifecycleCoordinator lifecycle;
  private final RunRecoveryService recoveryService;
  private final DiskUsageService diskUsageService;
  private final RunnerMetrics metrics;
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
  // Identity semantics: two exceptions may share overlapping process handles, but each failed
  // termination is its own reaped incident. Guarded exclusively by processLifecycleLock.
  private final Set<ProcessTerminationException> degradationIncidents =
      Collections.newSetFromMap(new IdentityHashMap<>());
  // Linearizes process launch against the whole termination attempt (not just the later
  // AVAILABLE->DEGRADED flip), since a failed termination is registered while still held.
  private final Object processLifecycleLock = new Object();

  public RunService(
      RunLifecycleStore store,
      RunLifecycleCoordinator lifecycle,
      RunRecoveryService recoveryService,
      DiskUsageService diskUsageService,
      RunnerMetrics metrics,
      MeterRegistry meterRegistry,
      ProcessLauncher processLauncher,
      ListenerEventIngestorFactory ingestorFactory,
      TestCatalogService testCatalogService,
      RunAvailabilityPolicy availabilityPolicy,
      RunnerProperties properties) {
    this.store = store;
    this.lifecycle = lifecycle;
    this.recoveryService = recoveryService;
    this.diskUsageService = diskUsageService;
    this.metrics = metrics;
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
    // Derived, not independently configured, so it can never drift below the submit-time floor
    // plus room for one more artifact.
    this.traceCaptureMinFreeBytes = properties.diskMinFreeBytes() + properties.artifactMaxBytes();
    this.executor =
        new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity));
    // Reflects STARTING + RUNNING + in-flight cleanup, not just RunStatus.RUNNING - read directly
    // from the executor's own state, with no separate counter to keep in sync.
    Gauge.builder("runner.executor.active", executor, ThreadPoolExecutor::getActiveCount)
        .register(meterRegistry);
    Gauge.builder("runner.executor.queued", executor, e -> e.getQueue().size())
        .register(meterRegistry);
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
      metrics.recordDiskRejection(RunnerMetrics.DiskRejectionPhase.SUBMIT);
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
    Runnable queuedTask =
        () ->
            MdcScope.withMdc(
                "runId",
                runId,
                () -> executeRun(runId, environment, suite, selectedTests, activeRun));
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

  /**
   * Non-throwing query (unlike {@link #submit}) - used by {@code RunnerAvailabilityHealthIndicator}
   * to report {@code OUT_OF_SERVICE} rather than {@code DOWN}.
   */
  public boolean isDegraded() {
    return availability.get() == Availability.DEGRADED;
  }

  public Path processLog(String runId) {
    final String safeRunId;
    try {
      safeRunId = RunFilePaths.requireSafeRunId(runId);
    } catch (IllegalArgumentException exception) {
      throw new RunLogNotFoundException(runId);
    }
    find(safeRunId);
    Path logFile;
    try {
      logFile = processLogPath(safeRunId);
      if (Files.isSymbolicLink(logFile)
          || !Files.isRegularFile(logFile, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("process log is missing or is not a regular file");
      }
      Path realLogsDir = logsDir.toRealPath();
      Path realLogFile = logFile.toRealPath();
      if (!realLogFile.startsWith(realLogsDir)
          || !Files.isRegularFile(realLogFile, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("process log resolves outside the configured logs directory");
      }
      return realLogFile;
    } catch (IllegalArgumentException | IOException exception) {
      throw new RunLogNotFoundException(safeRunId);
    }
  }

  /**
   * Requests cancellation. If the process has already launched, this sends the kill signal
   * synchronously but returns whatever status the run has at that instant - {@code executeRun}
   * still has to notice and record the terminal transition, so the result may still show {@code
   * RUNNING}; poll {@link #find} for the confirmed final status. A run removed from the executor
   * queue is the one case returned synchronously as {@code CANCELLED}.
   */
  public Run cancel(String runId) {
    // Wrapped so every log statement this (or anything it calls, e.g.
    // terminateWithinLifecycleGate) emits carries the real runId as a structured MDC field.
    return MdcScope.withMdc("runId", runId, () -> cancelInternal(runId));
  }

  private Run cancelInternal(String runId) {
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
        // Still queued - nothing to kill, just remove it. If executor.remove() loses the race (the
        // worker just claimed it), executeRun's own cancelRequested check records CANCELLED itself.
        if (executor.remove(queuedTask)) {
          try {
            lifecycle.finishIfLive(
                runId, RunStatus.CANCELLED, null, "Run was cancelled while queued", Instant.now());
          } finally {
            // The executor no longer owns this task, so tracking must be released even if
            // finishIfLive fails to persist the terminal event.
            activeRuns.remove(runId, activeRun);
          }
        }
        return find(runId);
      }
      // queuedTask already null: the worker cleared it at executeRun's start and is actively
      // launching, so the process publish is only instants away. Wait briefly to still terminate
      // it synchronously here, rather than silently no-op'ing and returning a stale
      // RUNNING/STARTING status.
      process = activeRun.awaitProcessPublished(PROCESS_PUBLISH_WAIT_TIMEOUT);
    }
    if (process == null) {
      // Never launched (cancelled/errored while waiting on availability, or start() failed) - the
      // worker already recorded its own terminal status.
      return find(runId);
    }
    try {
      terminateWithinLifecycleGate(process);
    } catch (ProcessTerminationException exception) {
      log.atError()
          .addKeyValue("runId", runId)
          .setCause(exception)
          .log("Could not terminate the process tree for cancelled run");
      // The worker may be stuck indefinitely in awaitCompletion() on the same unkillable process,
      // so its ingestor may still be forwarding events. Drain it here, before closing the journal
      // below, so the emergency finalization can't race ahead and silently drop them. Idempotent
      // if the worker later drains the same ingestor itself.
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
        // The worker is likely still blocked in awaitCompletion() on the same unkillable process
        // and would otherwise not notice for the full timeout - interrupt it unconditionally, even
        // if recording ERROR above failed. interruptWorkerIfAttached() is synchronized against
        // detach, so this can't land on a different run's worker after thread-pool reuse.
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
        // The worker won the race to claim this task before cancel() could remove it, so it owns
        // the terminal acknowledgement even though no process was launched.
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
        // Already recorded a terminal status (CANCELLED while waiting, or ERROR from start()
        // failing) inside the helper - nothing left to do.
        return;
      }

      // Started only once RUN_STARTED is durable - otherwise a TEST_* event already in the raw
      // file could be forwarded by the ingestor before RUN_STARTED wins the sequencing race. The
      // tailer reads from byte 0, so nothing written before this point is lost by waiting.
      boolean stillLive = lifecycle.markRunning(runId, Instant.now());
      if (stillLive) {
        ingestor = ingestorFactory.start(runId);
        // Attached before activeRun.process() is published, never after, so cancel() on another
        // thread can never see this run's process without also being able to find and drain its
        // ingestor - otherwise a cancel racing in between publish and this line could close the
        // journal while the ingestor is still forwarding events into it.
        activeRun.ingestor().set(ingestor);
      }
      activeRun.publishProcess(process);
      // Checked once, immediately after publish: this covers every cancellation that arrived
      // earlier (queued, during awaitAvailableThenStart, or during the setup above), before the
      // process was visible to cancel() at all. Any other placement leaves a gap where neither
      // side notices until awaitCompletion's full timeout elapses.
      if (activeRun.cancelRequested().get()) {
        terminateWithinLifecycleGate(process);
      }

      ProcessOutcome outcome = processLauncher.awaitCompletion(process, timeout);
      if (!stillLive) {
        // Finalized concurrently (e.g. cancelled) while this thread was launching/awaiting -
        // nothing left to record, and no ingestor was ever started to clean up.
        return;
      }
      // Stopped/drained before RUN_FINISHED below, so RUN_FINISHED is always the last event in a
      // run's timeline. Safe even if cancel() already stopped this ingestor concurrently -
      // stopAndAwaitFinished is idempotent once finished.
      IngestionResult ingestion = ingestor.stopAndAwaitFinished(ingestionDrainTimeout);

      RunStatus finalStatus = classify(outcome, activeRun.cancelRequested().get());
      String detail = detailFor(outcome, finalStatus);
      boolean interruptedOutcome =
          finalStatus == RunStatus.CANCELLED || finalStatus == RunStatus.TIMED_OUT;

      if (!ingestion.valid()) {
        // A malformed line, a sequence gap/duplicate, or a wrong-runId/non-test event means the
        // raw stream's own consistency broke down - no classification built from it, including
        // the exit code, is trustworthy any more.
        finalStatus = RunStatus.ERROR;
        detail = "Listener event ingestion failed: " + ingestion.detail();
      } else if (!ingestion.sawCompletionMarker()) {
        if (interruptedOutcome) {
          // Expected: the JVM may have been killed before the listener closed its writer. Note the
          // stream may be incomplete, but don't discard an otherwise-legitimate result.
          detail =
              (detail == null ? "" : detail + "; ")
                  + "raw event stream is incomplete because the process was interrupted before the"
                  + " listener closed it";
        } else if (finalStatus == RunStatus.SUCCEEDED) {
          // A SUCCEEDED (exit 0) classification is only trustworthy if the event log agrees the
          // run completed; a non-zero exit already proves failure without this check.
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
        // Best-effort: the run is already being recorded ERROR regardless, but the ingestion
        // thread must not outlive this run's own lifecycle.
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
   * Blocks the worker while the runner is {@link Availability#DEGRADED}, then atomically
   * re-confirms {@link Availability#AVAILABLE} and launches under the same lifecycle gate used by
   * termination, so an in-progress termination always finishes (and its incident becomes visible)
   * first. Returns {@code null} once a terminal status was already recorded (cancelled,
   * interrupted, or a {@code start()} failure) - the caller has nothing further to do.
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
        // submit()-time's disk check alone doesn't protect an already-queued run: several requests
        // can pass it before the first actually consumes disk. Re-checked here, right before
        // launch; unlike DEGRADED above this never loops/retries - a run that can't safely start
        // is terminalized now, not launched or waited out.
        DiskUsageSnapshot preLaunchUsage = diskUsageService.snapshot();
        if (preLaunchUsage.belowThreshold()) {
          metrics.recordDiskRejection(RunnerMetrics.DiskRejectionPhase.PRE_LAUNCH);
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
          // Threaded down from this same RunnerProperties config so a dashboard-launched run's
          // producer-side limits always agree with the consumer-side limits checked later; a
          // standalone Gradle invocation falls back to TestConfig's own defaults instead.
          Map<String, String> environment =
              Map.of(
                  "ARTIFACTS_DIR", reserveArtifactsDirectory(runId).toString(),
                  "ARTIFACT_MAX_BYTES", String.valueOf(artifactMaxBytes),
                  "RUN_MAX_TOTAL_ARTIFACT_BYTES", String.valueOf(runMaxTotalArtifactBytes),
                  "MANIFEST_MAX_BYTES", String.valueOf(manifestMaxBytes),
                  "TRACE_CAPTURE_MIN_FREE_BYTES", String.valueOf(traceCaptureMinFreeBytes),
                  // An env var, not a -D flag, so it's inherited automatically by the forked JUnit
                  // worker JVM that RunnerEventWriterRegistry runs in - no build.gradle forwarding
                  // needed.
                  "RUNNER_RAW_EVENT_MAX_BYTES", String.valueOf(rawEventMaxBytes),
                  "ALLURE_ATTACHMENTS_ENABLED", "false",
                  // Video recording bypasses ArtifactManifestWriter's caps entirely (Playwright
                  // writes it directly on context close), so it's forced off here regardless of
                  // the host's own RECORD_VIDEO setting.
                  "RECORD_VIDEO", "false");
          return processLauncher.start(
              runId, command, repoRoot, processLogPath(runId), environment);
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
   * Every run gets its own isolated, never-reused subdirectory. Uses {@code Files.createDirectory}
   * (atomic) rather than exists-then-create, so exactly one caller for a given {@code runId} ever
   * succeeds. Left in place on a later start failure - it belongs to that run's {@code ERROR}
   * outcome and may still gain a diagnostic entry.
   */
  // Package-private so RunServiceTest can exercise the reservation race directly, since triggering
  // it through the full async submit() flow isn't deterministic.
  Path reserveArtifactsDirectory(String runId) throws IOException {
    Files.createDirectories(artifactsRootDir);
    Path dir = RunFilePaths.artifactsDirectory(artifactsRootDir, runId);
    try {
      return Files.createDirectory(dir);
    } catch (FileAlreadyExistsException exception) {
      throw new IllegalStateException(
          "Artifacts directory already exists for run " + runId + ": " + dir, exception);
    }
  }

  private Path processLogPath(String runId) {
    return RunFilePaths.processLog(logsDir, runId);
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
