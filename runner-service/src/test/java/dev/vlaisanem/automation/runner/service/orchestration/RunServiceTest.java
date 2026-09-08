package dev.vlaisanem.automation.runner.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunOutcome;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.artifacts.ArtifactIngestionService;
import dev.vlaisanem.automation.runner.service.artifacts.FakeArtifactRepository;
import dev.vlaisanem.automation.runner.service.catalog.RunAvailabilityPolicy;
import dev.vlaisanem.automation.runner.service.catalog.RunAvailabilityPolicy.DeploymentProfile;
import dev.vlaisanem.automation.runner.service.catalog.TestCatalogService;
import dev.vlaisanem.automation.runner.service.config.RateLimitRule;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService.DiskUsageSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.domain.TestLayer;
import dev.vlaisanem.automation.runner.service.events.ListenerEventIngestor;
import dev.vlaisanem.automation.runner.service.events.ListenerEventIngestorFactory;
import dev.vlaisanem.automation.runner.service.events.RunEventBroker;
import dev.vlaisanem.automation.runner.service.exception.ProcessTerminationException;
import dev.vlaisanem.automation.runner.service.exception.RunNotFoundException;
import dev.vlaisanem.automation.runner.service.exception.RunQueueFullException;
import dev.vlaisanem.automation.runner.service.exception.RunnerDegradedException;
import dev.vlaisanem.automation.runner.service.exception.UnsupportedRunCombinationException;
import dev.vlaisanem.automation.runner.service.process.ProcessLauncher;
import dev.vlaisanem.automation.runner.service.process.ProcessOutcome;
import dev.vlaisanem.automation.runner.service.repository.FailFirstTransitionStore;
import dev.vlaisanem.automation.runner.service.repository.FailingRunLifecycleStore;
import dev.vlaisanem.automation.runner.service.repository.FakeRunLifecycleStore;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link RunService}'s orchestration - state transitions, cancellation, queue capacity -
 * against a {@link FakeProcessLauncher} rather than a real Gradle invocation, so the tricky
 * concurrent parts (cancel racing completion, timeout, queue-full) are deterministic and fast.
 *
 * <p>D2.3 cutover: rewritten against {@link FakeRunLifecycleStore} (behaviorally faithful to {@code
 * JdbcRunStore}, proven separately against a real Postgres in {@code databaseIntegrationTest})
 * instead of the retired in-memory {@code RunRepository}/file-backed {@code RunEventAppender} pair.
 * Every event-bearing test now goes through a real {@link RunEventBroker} wrapping that fake store
 * (the same production wiring, minus only the live Hub publish's actual transport), so {@code
 * service}'s own event assertions read the fake store directly rather than a separate recording
 * appender.
 */
class RunServiceTest {

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  /**
   * None of this class's scenarios ever produce a real {@code TEST_FAILED}/{@code TEST_ABORTED}/
   * {@code RUN_FINISHED} manifest to ingest, so {@link RunEventBroker}'s and {@link
   * RunLifecycleCoordinator}'s own D2.4 artifact-ingestion hooks never do anything observable here
   * - a real {@link ArtifactIngestionService} wired to an in-memory {@link FakeArtifactRepository}
   * satisfies each constructor without needing a real manifest file or database.
   */
  private static ArtifactIngestionService noopArtifactIngestionService(
      RunnerProperties properties) {
    return new ArtifactIngestionService(OBJECT_MAPPER, new FakeArtifactRepository(), properties);
  }

  /**
   * D2.5 - {@code RunService#submit} now requires {@link
   * RunRecoveryService#requireRecoveryComplete} to have already passed. None of this class's
   * scenarios exercise recovery itself (that lives in {@code RunRecoveryServiceTest}), so this runs
   * the real, one-time recovery pass immediately against the given (already fully set up)
   * store/coordinator - against an empty or already-terminal-only store, it finds nothing to
   * recover and completes instantly, exactly as it would against a real fresh Postgres with no
   * stale runs.
   */
  private static RunRecoveryService recoveryAlreadyComplete(
      RunLifecycleStore store, RunLifecycleCoordinator lifecycle) {
    RunRecoveryService recoveryService = new RunRecoveryService(store, lifecycle);
    recoveryService.run(null);
    return recoveryService;
  }

  /**
   * A {@link DiskUsageService} that never touches a real filesystem/database - its real constructor
   * only resolves configured directory paths (no I/O), and {@code snapshot()} is overridden here so
   * the real {@code FileStore}/{@code JdbcTemplate} calls it would otherwise make are never
   * reached. Mirrors this file's existing {@code FakeRunLifecycleStore}/{@code FakeProcessLauncher}
   * style for a collaborator this test suite needs to fully control.
   */
  private static DiskUsageService fixedDiskUsageService(DiskUsageSnapshot fixed) {
    return new DiskUsageService(minimalDiskUsageProperties(), null) {
      @Override
      public DiskUsageSnapshot snapshot() {
        return fixed;
      }
    };
  }

  private static DiskUsageService alwaysAvailableDiskUsageService() {
    return fixedDiskUsageService(
        new DiskUsageSnapshot(Long.MAX_VALUE, 1_048_576L, 314_572_800L, Instant.now()));
  }

  private static DiskUsageService belowThresholdDiskUsageService() {
    return fixedDiskUsageService(
        new DiskUsageSnapshot(0L, 1_048_576L, 314_572_800L, Instant.now()));
  }

  /**
   * Returns {@code snapshots} in order, one per call, then repeats the last one forever - used to
   * prove the pre-launch guard is a genuinely separate check from the submit-time one: a run that
   * passed the first (submit-time) snapshot can still be terminalized if a later (pre-launch)
   * snapshot reports disk has since dropped below threshold.
   */
  private static DiskUsageService sequencedDiskUsageService(DiskUsageSnapshot... snapshots) {
    RunnerProperties minimalProperties = minimalDiskUsageProperties();
    java.util.concurrent.atomic.AtomicInteger callCount =
        new java.util.concurrent.atomic.AtomicInteger();
    return new DiskUsageService(minimalProperties, null) {
      @Override
      public DiskUsageSnapshot snapshot() {
        int index = Math.min(callCount.getAndIncrement(), snapshots.length - 1);
        return snapshots[index];
      }
    };
  }

  private static RunnerProperties minimalDiskUsageProperties() {
    RateLimitRule aRule = new RateLimitRule(5, Duration.ofMinutes(1));
    return new RunnerProperties(
        ".",
        Duration.ofMinutes(10),
        "build/runner-events/raw",
        "build/runner-logs",
        "src/test/resources/catalog/public-test-catalog.json",
        "build/runner-artifacts",
        1024 * 1024,
        Duration.ofSeconds(5),
        Duration.ofSeconds(2),
        5,
        Duration.ofMillis(150),
        Duration.ofSeconds(5),
        100,
        Duration.ofSeconds(15),
        Duration.ofMinutes(10),
        aRule,
        aRule,
        aRule,
        aRule,
        aRule,
        aRule,
        aRule,
        3,
        16384,
        Duration.ofDays(30),
        500,
        Duration.ofDays(14),
        Duration.ofHours(1),
        aRule,
        1_048_576L,
        26_214_400L,
        209_715_200L,
        2_097_152L,
        2_097_152L,
        104_857_600L,
        aRule);
  }

  private RunService service;
  private FakeRunLifecycleStore store;

  @AfterEach
  void shutdown() {
    if (service != null) {
      service.shutdown();
    }
  }

  @Test
  void succeedsWhenProcessExitsZeroAndEventLogIsMarkedComplete(@TempDir Path eventsDir)
      throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    service = newService(launcher, eventsDir, 5);

    Run submitted = service.submit(Environment.PUBLIC, Suite.SMOKE);
    awaitStatus(submitted.runId(), RunStatus.RUNNING);
    // The real listener always creates the data file before the marker - an ingestor now rejects a
    // marker with no data file at all as an orphan, so the fake here must match that invariant.
    Files.createFile(eventsDir.resolve(submitted.runId() + ".tests.jsonl"));
    Files.createFile(eventsDir.resolve(submitted.runId() + ".tests.complete"));
    launcher.lastProcess().exitNow(0);

    Run finished = awaitTerminal(submitted.runId());

    assertThat(finished.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(finished.exitCode()).isEqualTo(0);
  }

  /**
   * Regression test for the review's finding: {@code SuiteCommandFactoryTest} proves {@code
   * RunCatalog} maps {@code LOCAL}+{@code JOURNEY} to {@code localJourneyTest} in isolation, but
   * nothing previously proved {@code RunService.submit} actually carries the caller's chosen {@link
   * Environment} through {@code executeRun} into the command it hands the launcher - every other
   * test here submits {@link Environment#PUBLIC}. Confirms the launched command names the dedicated
   * local task and never the public {@code journeyTest} task.
   */
  @Test
  void submitPassesTheSelectedLocalEnvironmentThroughToTheLaunchedCommand(@TempDir Path eventsDir)
      throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    service = newService(launcher, eventsDir, 5);

    Run submitted = service.submit(Environment.LOCAL, Suite.JOURNEY);
    awaitStatus(submitted.runId(), RunStatus.RUNNING);

    assertThat(launcher.startedCommands).hasSize(1);
    assertThat(launcher.startedCommands.get(0)).contains("localJourneyTest");
    assertThat(launcher.startedCommands.get(0)).doesNotContain("journeyTest");
  }

  /**
   * Full-chain regression test for the review's finding: nothing previously exercised catalog load
   * -> {@code CustomTestSelectionValidator} -> immutable {@link SelectedTestSnapshot} -> the actual
   * launched {@code customTest --tests ...} command together. A second, unselected catalog entry
   * proves the launched command carries exactly the selected filter and nothing else - not a
   * coincidentally-passing full {@code customTest} invocation.
   */
  @Test
  void submitsACustomRunWithExactlyTheSelectedTestsAndNothingElse(
      @TempDir Path eventsDir, @TempDir Path catalogDir) throws Exception {
    Path catalogFile = catalogDir.resolve("catalog.json");
    Files.writeString(
        catalogFile,
        """
        {
          "tests": [
            {
              "testKey": "some.ApiTest#methodOne",
              "displayName": "First selected test",
              "category": "API",
              "tags": ["regression", "read-only", "api"]
            },
            {
              "testKey": "some.UiTest#methodTwo",
              "displayName": "Second, unselected test",
              "category": "UI",
              "tags": ["regression", "read-only", "ui"]
            }
          ]
        }
        """);
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    service = newServiceWithCatalog(launcher, eventsDir, catalogFile, 5);

    Run submitted =
        service.submit(Environment.PUBLIC, Suite.CUSTOM, List.of("some.ApiTest#methodOne"));
    awaitStatus(submitted.runId(), RunStatus.RUNNING);

    assertThat(submitted.selectedTests())
        .containsExactly(
            new SelectedTestSnapshot(
                "some.ApiTest#methodOne", "First selected test", TestLayer.API));
    assertThat(launcher.startedCommands).hasSize(1);
    List<String> command = launcher.startedCommands.get(0);
    assertThat(command).contains("customTest");
    assertThat(command).containsSequence("--tests", "some.ApiTest.methodOne");
    assertThat(command).doesNotContain("some.UiTest.methodTwo");
  }

  /**
   * Regression test for the review's finding: an invalid {@code CUSTOM} selection must fail before
   * any of a run's usual side effects happen - {@code submit()}'s own code path only generates a
   * {@code runId} and calls {@code lifecycle.queue} (which is what emits {@code RUN_QUEUED}) after
   * {@code CustomTestSelectionValidator.validate} has already succeeded, so a rejected selection
   * must leave the store empty (which, thanks to the real schema's own foreign key, structurally
   * implies no event could possibly exist either) and (implicitly, since no runId or {@code
   * ActiveRun} is ever created) nothing tracked in {@code activeRuns}.
   */
  @Test
  void anInvalidCustomSelectionNeverSavesARunOrEmitsAnyEvent(
      @TempDir Path eventsDir, @TempDir Path catalogDir) throws Exception {
    Path catalogFile = catalogDir.resolve("catalog.json");
    Files.writeString(
        catalogFile,
        """
        {
          "tests": [
            {
              "testKey": "some.ApiTest#methodOne",
              "displayName": "First selected test",
              "category": "API",
              "tags": ["regression", "read-only", "api"]
            }
          ]
        }
        """);
    FakeRunLifecycleStore fakeStore = new FakeRunLifecycleStore();
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    service = newServiceWithCatalog(fakeStore, launcher, eventsDir, catalogFile, 5);

    assertThatThrownBy(
            () ->
                service.submit(
                    Environment.PUBLIC, Suite.CUSTOM, List.of("unknown.Test#doesNotExist")))
        .isInstanceOf(InvalidTestSelectionException.class);

    assertThat(fakeStore.findAll()).isEmpty();
    assertThat(launcher.startedCommands).isEmpty();
  }

  /**
   * Regression test for the review's finding: every other test in this class constructs {@code
   * RunService} with {@link RunAvailabilityPolicy#localDev()}, so nothing here previously proved
   * that {@code submit()} actually consults the deployment's own policy at all - a future change
   * that accidentally dropped {@code RunRequestValidator.validate(availabilityPolicy, ...)} from
   * {@code RunService.submit} would have left {@code RunAvailabilityPolicyTest}/ {@code
   * RunRequestValidatorTest}/{@code CapabilitiesResponseTest} all still green, since none of them
   * exercise {@code RunService} itself. Mirrors {@link
   * #anInvalidCustomSelectionNeverSavesARunOrEmitsAnyEvent}'s own shape: the rejection happens
   * before a {@code runId} is even generated (see {@code RunService.submit}'s own ordering), so no
   * run is saved, no {@code RUN_QUEUED} event is emitted, and the process launcher is never invoked
   * - nothing is left to assert about {@code activeRuns} directly since no entry for it could
   * possibly have been created yet.
   */
  @Test
  void rejectsALocalSubmissionUnderThePortfolioProfileWithNoSideEffects(@TempDir Path eventsDir)
      throws Exception {
    FakeRunLifecycleStore fakeStore = new FakeRunLifecycleStore();
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    service =
        newServiceWithPolicy(
            fakeStore,
            launcher,
            eventsDir,
            5,
            new RunAvailabilityPolicy(DeploymentProfile.PORTFOLIO));

    assertThatThrownBy(() -> service.submit(Environment.LOCAL, Suite.JOURNEY))
        .isInstanceOf(UnsupportedRunCombinationException.class);

    assertThat(fakeStore.findAll()).isEmpty();
    assertThat(launcher.startedCommands).isEmpty();
  }

  /**
   * The companion half of {@link #rejectsALocalSubmissionUnderThePortfolioProfileWithNoSideEffects}
   * - proves the portfolio profile narrows {@code LOCAL} specifically, not every submission, by
   * running a real {@code PUBLIC} request all the way to a launched process under the same policy.
   */
  @Test
  void allowsAPublicSubmissionUnderThePortfolioProfile(@TempDir Path eventsDir) throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    service =
        newServiceWithPolicy(
            new FakeRunLifecycleStore(),
            launcher,
            eventsDir,
            5,
            new RunAvailabilityPolicy(DeploymentProfile.PORTFOLIO));

    Run submitted = service.submit(Environment.PUBLIC, Suite.SMOKE);
    awaitStatus(submitted.runId(), RunStatus.RUNNING);

    assertThat(launcher.startedCommands).hasSize(1);
    assertThat(launcher.startedCommands.get(0)).contains("smokeTest");
  }

  @Test
  void startsEachRunWithItsOwnArtifactsDirectoryPassedAsAnEnvironmentVariable(
      @TempDir Path eventsDir) throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    service = newService(launcher, eventsDir, 5);

    Run submitted = service.submit(Environment.PUBLIC, Suite.SMOKE);
    awaitStatus(submitted.runId(), RunStatus.RUNNING);

    assertThat(launcher.startedEnvironments).hasSize(1);
    Path expectedDir = eventsDir.resolve("artifacts").resolve(submitted.runId());
    assertThat(launcher.startedEnvironments.get(0))
        .containsEntry("ARTIFACTS_DIR", expectedDir.toString());
    // Actually created on disk, not just computed - see reserveArtifactsDirectory's own atomic
    // Files.createDirectory, the fix for the review's finding that a plain Files.exists check left
    // a check-then-act race between two callers.
    assertThat(expectedDir).isDirectory();
  }

  @Test
  void refusesToReserveTheSameArtifactsDirectoryTwice(@TempDir Path eventsDir) throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    service = newService(launcher, eventsDir, 5);
    String runId = "already-reserved";

    service.reserveArtifactsDirectory(runId);

    assertThatThrownBy(() -> service.reserveArtifactsDirectory(runId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(runId);
  }

  /**
   * Regression test for the review's finding: the previous {@code Files.exists} check followed by a
   * separate creation left a window in which two callers could both see "does not exist yet" and
   * both proceed. {@code Files.createDirectory} is atomic - exactly one of any number of concurrent
   * callers for the same {@code runId} can ever succeed, which this drives with a real race (both
   * threads released by the same latch) rather than trusting the atomicity claim un-exercised.
   */
  @Test
  void exactlyOneOfTwoConcurrentReservationsForTheSameRunIdSucceeds(@TempDir Path eventsDir)
      throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    service = newService(launcher, eventsDir, 5);
    String runId = "raced-run-id";
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> attempts =
          List.of(
              pool.submit(() -> attemptReservation(runId, ready, release)),
              pool.submit(() -> attemptReservation(runId, ready, release)));
      ready.await();
      release.countDown();

      long succeeded = attempts.stream().filter(RunServiceTest::futureResult).count();
      assertThat(succeeded).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  private boolean attemptReservation(String runId, CountDownLatch ready, CountDownLatch release)
      throws Exception {
    ready.countDown();
    release.await();
    try {
      service.reserveArtifactsDirectory(runId);
      return true;
    } catch (IllegalStateException expectedForTheLoser) {
      return false;
    }
  }

  private static boolean futureResult(Future<Boolean> future) {
    try {
      return future.get();
    } catch (Exception exception) {
      throw new RuntimeException(exception);
    }
  }

  /**
   * D2.3 cutover: replaces the pre-cutover "emergency ERROR" test for this same failure point. With
   * one atomic store transaction, a permanently failing {@code RUN_FINISHED} write can no longer be
   * worked around by a separate, always-available side channel the way the old repository-only
   * emergency write could - see {@code RunLifecycleCoordinator}'s own Javadoc for why removing that
   * mechanism is a deliberate consequence of the cutover, not an oversight. Both the original
   * SUCCEEDED write and {@code executeRun}'s own fallback ERROR write need a {@code RUN_FINISHED}
   * event, so both fail identically here, leaving the run stuck at its last known-good status
   * (RUNNING) - never falsely SUCCEEDED, which is the one invariant this test still exists to
   * prove. The process is still terminated as part of cleanup, and - proving the failure is scoped
   * to this one run's write, not a global "journal closed" flag the way the old mechanism worked -
   * a different run can still be submitted normally afterward.
   */
  @Test
  void aPermanentlyFailingRunFinishedWriteLeavesTheRunStuckRunningNeverFalselySucceeded(
      @TempDir Path eventsDir) throws Exception {
    FakeRunLifecycleStore fakeStore = new FakeRunLifecycleStore();
    FailingRunLifecycleStore failingStore =
        new FailingRunLifecycleStore(fakeStore, EventType.RUN_FINISHED);
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    service = newService(failingStore, launcher, eventsDir, 5, Duration.ofSeconds(30));

    Run submitted = service.submit(Environment.PUBLIC, Suite.SMOKE);
    awaitStatus(submitted.runId(), RunStatus.RUNNING);
    Files.createFile(eventsDir.resolve(submitted.runId() + ".tests.jsonl"));
    Files.createFile(eventsDir.resolve(submitted.runId() + ".tests.complete"));
    launcher.lastProcess().exitNow(0);

    Thread.sleep(300); // give the worker every chance to (wrongly) reach a terminal status
    assertThat(service.find(submitted.runId()).status()).isEqualTo(RunStatus.RUNNING);
    assertThat(launcher.lastProcess().wasDestroyed()).isTrue();

    assertThatCode(() -> service.submit(Environment.PUBLIC, Suite.API)).doesNotThrowAnyException();
  }

  @Test
  void classifiesANonZeroExitAsFailed(@TempDir Path eventsDir) throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    service = newService(launcher, eventsDir, 5);

    Run submitted = service.submit(Environment.PUBLIC, Suite.API);
    awaitStatus(submitted.runId(), RunStatus.RUNNING);
    launcher.lastProcess().exitNow(1);

    Run finished = awaitTerminal(submitted.runId());

    assertThat(finished.status()).isEqualTo(RunStatus.FAILED);
    assertThat(finished.exitCode()).isEqualTo(1);
  }

  @Test
  void reclassifiesAZeroExitAsErrorWhenTheEventLogWasNeverMarkedComplete(@TempDir Path eventsDir)
      throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    service = newService(launcher, eventsDir, 5);

    Run submitted = service.submit(Environment.PUBLIC, Suite.UI);
    awaitStatus(submitted.runId(), RunStatus.RUNNING);
    // Deliberately no .complete marker created here.
    launcher.lastProcess().exitNow(0);

    Run finished = awaitTerminal(submitted.runId());

    assertThat(finished.status()).isEqualTo(RunStatus.ERROR);
    assertThat(finished.detail()).contains("never marked complete");
  }

  @Test
  void classifiesAProcessStartupFailureAsError(@TempDir Path eventsDir) throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    launcher.failToStart = true;
    service = newService(launcher, eventsDir, 5);

    Run submitted = service.submit(Environment.PUBLIC, Suite.JOURNEY);

    Run finished = awaitTerminal(submitted.runId());

    assertThat(finished.status()).isEqualTo(RunStatus.ERROR);
    assertThat(finished.detail()).contains("Failed to start process");
  }

  @Test
  void cancelKillsARunningProcessAndReportsCancelled(@TempDir Path eventsDir) throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    service = newService(launcher, eventsDir, 5);

    Run submitted = service.submit(Environment.PUBLIC, Suite.REGRESSION);
    awaitStatus(submitted.runId(), RunStatus.RUNNING);

    // cancel() sends the kill signal synchronously but does not wait for executeRun's worker
    // thread to notice and record the terminal transition - see RunService.cancel()'s Javadoc.
    service.cancel(submitted.runId());
    assertThat(launcher.lastProcess().wasDestroyed()).isTrue();

    Run finished = awaitTerminal(submitted.runId());
    assertThat(finished.status()).isEqualTo(RunStatus.CANCELLED);
  }

  @Test
  void cancelMarksAStillQueuedRunCancelledWithoutTouchingAnyProcess(@TempDir Path eventsDir)
      throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    service = newService(launcher, eventsDir, 1);

    Run occupying = service.submit(Environment.PUBLIC, Suite.SMOKE);
    awaitStatus(occupying.runId(), RunStatus.RUNNING); // worker is now busy indefinitely
    Run queued = service.submit(Environment.PUBLIC, Suite.API);
    assertThat(service.find(queued.runId()).status()).isEqualTo(RunStatus.QUEUED);

    Run cancelled = service.cancel(queued.runId());

    assertThat(cancelled.status()).isEqualTo(RunStatus.CANCELLED);
    Run replacement = service.submit(Environment.PUBLIC, Suite.UI);
    assertThat(service.find(replacement.runId()).status()).isEqualTo(RunStatus.QUEUED);
  }

  @Test
  void cancelDuringProcessStartupIsAcknowledgedByTheWorker(@TempDir Path eventsDir)
      throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    launcher.blockProcessStart = true;
    service = newService(launcher, eventsDir, 5);

    Run submitted = service.submit(Environment.PUBLIC, Suite.SMOKE);
    assertThat(launcher.startEntered.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(service.find(submitted.runId()).status()).isEqualTo(RunStatus.STARTING);

    Run cancellationInFlight = service.cancel(submitted.runId());

    assertThat(cancellationInFlight.status()).isEqualTo(RunStatus.STARTING);
    launcher.allowProcessStart.countDown();
    Run finished = awaitTerminal(submitted.runId());
    assertThat(finished.status()).isEqualTo(RunStatus.CANCELLED);
    assertThat(launcher.lastProcess().wasDestroyed()).isTrue();
  }

  @Test
  void failedCancellationIsRecordedAsErrorWithTheSurvivingPids(@TempDir Path eventsDir)
      throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    launcher.failTermination = true;
    service = newService(launcher, eventsDir, 5);
    Run submitted = service.submit(Environment.PUBLIC, Suite.REGRESSION);
    awaitStatus(submitted.runId(), RunStatus.RUNNING);

    Run result = service.cancel(submitted.runId());

    assertThat(result.status()).isEqualTo(RunStatus.ERROR);
    assertThat(result.detail()).contains(Long.toString(FakeProcess.FIXED_PID));
  }

  /**
   * Regression test for the review's finding: previously, when termination failed during cancel,
   * the worker thread stayed blocked inside {@code awaitCompletion} on the same (unkillable)
   * process and would not notice for up to the full configured timeout - tying up the single worker
   * even though the run already reports as finished. A failed termination also now correctly
   * degrades the runner (see {@link
   * #degradedRunnerRejectsSubmissionsUntilTheSurvivorExitsThenRecovers} for that concern in
   * isolation), so a submission cannot succeed immediately after cancel() the way it could before
   * that safeguard existed - what this test isolates instead is that recovery, once the survivor
   * actually exits, happens within a short deadline rather than only after the full ~10-minute
   * timeout the (still-)stuck worker would otherwise need to notice on its own.
   */
  @Test
  void cancelInterruptsTheWorkerWhenTerminationFailsSoRecoveryDoesNotWaitTheFullTimeout(
      @TempDir Path eventsDir) throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    launcher.failTermination = true;
    service =
        newService(new FakeRunLifecycleStore(), launcher, eventsDir, 1, Duration.ofMinutes(10));

    Run stuck = service.submit(Environment.PUBLIC, Suite.REGRESSION);
    awaitStatus(stuck.runId(), RunStatus.RUNNING);
    FakeProcess survivor = launcher.lastProcess();

    Run cancelResult = service.cancel(stuck.runId());
    assertThat(cancelResult.status()).isEqualTo(RunStatus.ERROR);

    // Once the survivor actually exits, recovery - and therefore a new run reaching RUNNING -
    // must happen within a short deadline. If the worker were still stuck inside the 10-minute
    // awaitCompletion wait, the reaper flipping availability back on its own would not be enough:
    // the queued run would still have no free worker to pick it up for the rest of that timeout.
    survivor.exitNow(0);
    Run next = awaitRecoveredSubmit(Environment.PUBLIC, Suite.SMOKE);
    awaitStatus(next.runId(), RunStatus.RUNNING);
  }

  /**
   * Cleanup must not depend on the canonical store remaining writable. D2.3 cutover: if both
   * process termination and the {@code RUN_FINISHED} write fail, {@code cancel()} now propagates
   * whatever the store itself throws (there is no longer a dedicated {@code
   * RunEventPersistenceException} wrapper - see {@code RunLifecycleCoordinator}'s own Javadoc), and
   * - since the fallback {@code ERROR} write needs the same {@code RUN_FINISHED} event type and
   * therefore also fails - the run is left stuck non-terminal rather than falsely recorded as
   * {@code ERROR}. What still matters, and is still proven here, is that the worker thread itself
   * is not left blocked for the configured timeout regardless.
   */
  @Test
  void cancelStillInterruptsTheWorkerWhenTerminationAndTerminalEventPersistenceBothFail(
      @TempDir Path eventsDir) throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    launcher.failTermination = true;
    FakeRunLifecycleStore fakeStore = new FakeRunLifecycleStore();
    FailingRunLifecycleStore failingStore =
        new FailingRunLifecycleStore(fakeStore, EventType.RUN_FINISHED);
    service = newService(failingStore, launcher, eventsDir, 1, Duration.ofMinutes(10));

    Run stuck = service.submit(Environment.PUBLIC, Suite.REGRESSION);
    awaitStatus(stuck.runId(), RunStatus.RUNNING);

    assertThatThrownBy(() -> service.cancel(stuck.runId())).isInstanceOf(RuntimeException.class);

    assertThat(launcher.awaitCompletionFinished.await(5, TimeUnit.SECONDS))
        .as("worker must leave awaitCompletion without waiting for the ten-minute timeout")
        .isTrue();
    assertThat(service.find(stuck.runId()).status()).isEqualTo(RunStatus.RUNNING);
  }

  /**
   * Regression test for two related races. First, a slow cancel must not retain a stale worker
   * reference and later interrupt an unrelated run after the executor reuses its thread. Second,
   * and more importantly, that unrelated run must not launch at all while termination is still
   * unresolved: the failure and its DEGRADED incident have to be registered under the same
   * lifecycle gate before the next process can start.
   */
  @Test
  void anUnresolvedTerminationBlocksTheNextLaunchUntilItsSurvivorIsReaped(@TempDir Path eventsDir)
      throws Exception {
    CountDownLatch terminateEntered = new CountDownLatch(1);
    CountDownLatch releaseTerminate = new CountDownLatch(1);
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    FakeProcess survivor = new FakeProcess(778L);
    launcher.terminationFailures.add(new ProcessTerminationException(survivor, List.of()));
    launcher.terminateBlocker =
        () -> {
          terminateEntered.countDown();
          awaitUninterruptibly(releaseTerminate);
        };
    service = newService(launcher, eventsDir, 1);

    Run first = service.submit(Environment.PUBLIC, Suite.REGRESSION);
    awaitStatus(first.runId(), RunStatus.RUNNING);

    Thread cancelThread = new Thread(() -> service.cancel(first.runId()));
    cancelThread.start();
    assertThat(terminateEntered.await(5, TimeUnit.SECONDS)).isTrue();

    // While cancel() owns the lifecycle gate inside terminate(), let the first run finish naturally
    // and free the single worker for a second run.
    launcher.lastProcess().exitNow(0);
    awaitTerminal(first.runId());

    Run second = service.submit(Environment.PUBLIC, Suite.SMOKE);
    awaitStatus(second.runId(), RunStatus.STARTING);

    // The second worker reached the launch boundary but cannot invoke ProcessLauncher.start while
    // the first termination is unresolved.
    Thread.sleep(200);
    assertThat(launcher.startedCommands).hasSize(1);

    // The failed termination registers DEGRADED before releasing the lifecycle gate. The late
    // interrupt is also a no-op because the first run already detached its worker.
    releaseTerminate.countDown();
    cancelThread.join(5000);
    assertThat(cancelThread.isAlive()).isFalse();

    // The gate is free now, but the known survivor still keeps the next process from launching.
    Thread.sleep(200);
    assertThat(service.find(second.runId()).status()).isEqualTo(RunStatus.STARTING);
    assertThat(launcher.startedCommands).hasSize(1);

    survivor.exitNow(0);
    awaitStatus(second.runId(), RunStatus.RUNNING);
    assertThat(launcher.startedCommands).hasSize(2);
  }

  /**
   * Regression test for the review's finding: a cancellation racing in during the window between
   * markRunning/ingestor setup and activeRun.process() being published must still be honored
   * promptly by this thread's own post-publish check - not only once awaitCompletion's full (here,
   * 10-minute) timeout eventually elapses. Blocks the worker exactly inside {@code
   * ListenerEventIngestorFactory.start} - before the ingestor is attached to activeRun and
   * therefore before the process is published either - to deterministically land a concurrent
   * cancel() call in that window, where it cannot yet see (or act on) the process directly.
   */
  @Test
  void cancelRacingRightBeforeProcessPublicationStillTerminatesPromptly(@TempDir Path eventsDir)
      throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    CountDownLatch ingestorStartEntered = new CountDownLatch(1);
    CountDownLatch releaseIngestorStart = new CountDownLatch(1);
    RunnerProperties properties =
        new RunnerProperties(
            ".",
            Duration.ofMinutes(10),
            eventsDir.toString(),
            eventsDir.resolve("logs").toString(),
            "src/test/resources/catalog/public-test-catalog.json",
            eventsDir.resolve("artifacts").toString(),
            1024 * 1024,
            Duration.ofMillis(50),
            Duration.ofMillis(20),
            1,
            Duration.ofMillis(20),
            Duration.ofSeconds(2),
            10_000,
            Duration.ofSeconds(15),
            Duration.ofMinutes(10),
            new RateLimitRule(5, Duration.ofMinutes(1)),
            new RateLimitRule(10, Duration.ofMinutes(1)),
            new RateLimitRule(3, Duration.ofMinutes(1)),
            new RateLimitRule(10, Duration.ofHours(1)),
            new RateLimitRule(10, Duration.ofMinutes(1)),
            new RateLimitRule(120, Duration.ofMinutes(1)),
            new RateLimitRule(30, Duration.ofMinutes(1)),
            3,
            16384,
            Duration.ofDays(30),
            500,
            Duration.ofDays(14),
            Duration.ofHours(1),
            new RateLimitRule(10, Duration.ofHours(1)),
            1_048_576L,
            26_214_400L,
            209_715_200L,
            2_097_152L,
            2_097_152L,
            104_857_600L,
            new RateLimitRule(10, Duration.ofHours(1)));
    store = new FakeRunLifecycleStore();
    RunEventBroker broker =
        new RunEventBroker(store, properties, noopArtifactIngestionService(properties));
    RunLifecycleCoordinator lifecycle =
        new RunLifecycleCoordinator(broker, noopArtifactIngestionService(properties));
    ListenerEventIngestorFactory blockingIngestorFactory =
        new ListenerEventIngestorFactory(broker, OBJECT_MAPPER, properties) {
          @Override
          public ListenerEventIngestor start(String runId) {
            ingestorStartEntered.countDown();
            awaitUninterruptibly(releaseIngestorStart);
            return super.start(runId);
          }
        };
    service =
        new RunService(
            store,
            lifecycle,
            recoveryAlreadyComplete(store, lifecycle),
            alwaysAvailableDiskUsageService(),
            launcher,
            blockingIngestorFactory,
            new TestCatalogService(properties, OBJECT_MAPPER),
            RunAvailabilityPolicy.localDev(),
            properties);

    Run submitted = service.submit(Environment.PUBLIC, Suite.SMOKE);
    assertThat(ingestorStartEntered.await(5, TimeUnit.SECONDS)).isTrue();

    // The process is not yet published to activeRun at all here - this cannot see or terminate it
    // directly, so it can only set cancelRequested for the worker's own post-publish check to
    // honor once it resumes.
    Thread cancelThread = new Thread(() -> service.cancel(submitted.runId()));
    cancelThread.start();
    cancelThread.join(5000);
    assertThat(cancelThread.isAlive()).isFalse();

    releaseIngestorStart.countDown();

    // awaitTerminal's own 5-second deadline is what actually proves this did not fall back to
    // waiting the full 10-minute processTimeout configured above.
    Run finished = awaitTerminal(submitted.runId());

    assertThat(finished.status()).isEqualTo(RunStatus.CANCELLED);
    assertThat(launcher.lastProcess().wasDestroyed()).isTrue();
    List<RunnerEvent> recorded = store.readEventsAfter(submitted.runId(), 0);
    assertThat(recorded)
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.RUN_QUEUED, EventType.RUN_STARTED, EventType.RUN_FINISHED);
    assertThat(recorded.getLast().runOutcome()).isEqualTo(RunOutcome.CANCELLED);
  }

  /**
   * Regression test for the review's finding: a known-surviving process from a failed termination
   * must not simply free up the single-worker slot, or a new run could execute concurrently with it
   * and break single-run isolation. The runner must refuse new submissions until the survivor is
   * confirmed gone, then recover on its own once the background reaper notices.
   */
  @Test
  void degradedRunnerRejectsSubmissionsUntilTheSurvivorExitsThenRecovers(@TempDir Path eventsDir)
      throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    launcher.failTermination = true;
    service = newService(launcher, eventsDir, 1);

    Run first = service.submit(Environment.PUBLIC, Suite.REGRESSION);
    awaitStatus(first.runId(), RunStatus.RUNNING);
    FakeProcess survivor = launcher.lastProcess(); // kept alive deliberately - see below

    Run cancelled = service.cancel(first.runId());
    assertThat(cancelled.status()).isEqualTo(RunStatus.ERROR);

    // While the survivor is known to still be alive, a new submission must be refused rather than
    // risk running it concurrently with a leftover process from the previous run.
    assertThatThrownBy(() -> service.submit(Environment.PUBLIC, Suite.SMOKE))
        .isInstanceOf(RunnerDegradedException.class);

    // Now let the survivor actually die and wait for the background reaper to notice.
    survivor.exitNow(0);
    Run recovered = awaitRecoveredSubmit(Environment.PUBLIC, Suite.SMOKE);
    awaitStatus(recovered.runId(), RunStatus.RUNNING);
  }

  /** D4.2 - submit() itself must refuse a new run outright once disk is already below threshold. */
  @Test
  void submitRejectsWithDiskSpaceLowExceptionWhenDiskIsAlreadyBelowThreshold(
      @TempDir Path eventsDir) {
    service =
        newServiceWithDiskUsage(
            new FakeProcessLauncher(), eventsDir, 1, belowThresholdDiskUsageService());

    assertThatThrownBy(() -> service.submit(Environment.PUBLIC, Suite.SMOKE))
        .isInstanceOf(
            dev.vlaisanem.automation.runner.service.exception.DiskSpaceLowException.class);
  }

  /**
   * D4.2 - a submit()-time check alone does not protect a run that was already queued: disk can
   * drop below threshold in the window between accepting the submission and the single worker
   * actually getting to it. The pre-launch guard (immediately before {@code processLauncher.start})
   * must catch this and terminalize the run as {@code ERROR}, never silently launch it anyway.
   */
  @Test
  void aQueuedRunTerminalizesAsErrorWhenDiskDropsBelowThresholdBeforeLaunch(@TempDir Path eventsDir)
      throws Exception {
    DiskUsageSnapshot available =
        new DiskUsageSnapshot(Long.MAX_VALUE, 1_048_576L, 314_572_800L, Instant.now());
    DiskUsageSnapshot belowThreshold =
        new DiskUsageSnapshot(0L, 1_048_576L, 314_572_800L, Instant.now());
    // First call (submit()'s own check) reports available; every call after (the pre-launch
    // re-check) reports below threshold - proves the two are genuinely separate checks.
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    service =
        newServiceWithDiskUsage(
            launcher, eventsDir, 1, sequencedDiskUsageService(available, belowThreshold));

    Run run = service.submit(Environment.PUBLIC, Suite.SMOKE);

    Run finished = awaitTerminal(run.runId());
    assertThat(finished.status()).isEqualTo(RunStatus.ERROR);
    assertThat(finished.detail()).contains("disk space");
    assertThat(launcher.startedCommands)
        .as("the process must never actually launch once the pre-launch guard rejects it")
        .isEmpty();
  }

  /**
   * Regression test for the review's finding: {@code submit()}'s own availability check only guards
   * against a <em>new</em> submission - a run already dequeued by the worker before degradation
   * existed must not slip through and launch its own process while a survivor from a previous run
   * might still be alive. This queues the second run while the runner is still healthy (so {@code
   * submit()} legitimately accepts it), then triggers the degradation and confirms the second run
   * does not reach {@code RUNNING} until the survivor actually exits.
   */
  @Test
  void aRunAlreadyQueuedBeforeDegradationDoesNotStartUntilTheSurvivorExits(@TempDir Path eventsDir)
      throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    launcher.failTermination = true;
    service = newService(launcher, eventsDir, 1);

    Run first = service.submit(Environment.PUBLIC, Suite.REGRESSION);
    awaitStatus(first.runId(), RunStatus.RUNNING);
    FakeProcess survivor = launcher.lastProcess();

    // Accepted while the runner is still healthy - queued behind the single busy worker.
    Run second = service.submit(Environment.PUBLIC, Suite.SMOKE);
    assertThat(service.find(second.runId()).status()).isEqualTo(RunStatus.QUEUED);

    Run cancelled = service.cancel(first.runId());
    assertThat(cancelled.status()).isEqualTo(RunStatus.ERROR);

    // The runner is now degraded because of the still-alive survivor. Give the worker every
    // chance to (wrongly) pick up and launch the second run's process before asserting it hasn't.
    Thread.sleep(300);
    assertThat(service.find(second.runId()).status())
        .as("must not have launched its process while a survivor might still be alive")
        .isIn(RunStatus.QUEUED, RunStatus.STARTING);

    survivor.exitNow(0);
    awaitStatus(second.runId(), RunStatus.RUNNING);
  }

  /**
   * Regression test for the event-contract requirement: a run cancelled while it is still waiting
   * out a DEGRADED runner (never having reached RUNNING) must have a canonical timeline of exactly
   * {@code RUN_QUEUED} followed by {@code RUN_FINISHED(CANCELLED)} - no {@code RUN_STARTED} and no
   * {@code TEST_*} event in between, with a continuous sequence.
   */
  @Test
  void cancellingARunWhileItWaitsForDegradedRecoveryEmitsOnlyQueuedThenFinishedCancelled(
      @TempDir Path eventsDir) throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    launcher.failTermination = true;
    service = newService(launcher, eventsDir, 1);

    Run first = service.submit(Environment.PUBLIC, Suite.REGRESSION);
    awaitStatus(first.runId(), RunStatus.RUNNING);
    FakeProcess survivor = launcher.lastProcess();

    Run second = service.submit(Environment.PUBLIC, Suite.SMOKE);
    assertThat(service.find(second.runId()).status()).isEqualTo(RunStatus.QUEUED);

    Run cancelled = service.cancel(first.runId());
    assertThat(cancelled.status()).isEqualTo(RunStatus.ERROR);

    // The runner is now degraded; wait for the worker to pick up "second" and start waiting on it.
    awaitStatus(second.runId(), RunStatus.STARTING);

    service.cancel(second.runId());
    Run finished = awaitTerminal(second.runId());
    assertThat(finished.status()).isEqualTo(RunStatus.CANCELLED);

    List<RunnerEvent> recorded = store.readEventsAfter(second.runId(), 0);
    assertThat(recorded)
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.RUN_QUEUED, EventType.RUN_FINISHED);
    assertThat(recorded).noneMatch(event -> event.type().isTestLevel());
    assertThat(recorded).extracting(RunnerEvent::sequence).containsExactly(1L, 2L);
    assertThat(recorded.getLast().runOutcome()).isEqualTo(RunOutcome.CANCELLED);

    survivor.exitNow(0);
  }

  /**
   * Every failed termination owns a separate reaper incident. If incident A recovers while incident
   * B still has a live survivor, A's reaper must not globally flip the runner back to AVAILABLE.
   */
  @Test
  void recoveryFromOneDegradationIncidentDoesNotHideAnotherLiveSurvivor(@TempDir Path eventsDir)
      throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    service = newService(launcher, eventsDir, 1);

    Run run = service.submit(Environment.PUBLIC, Suite.REGRESSION);
    awaitStatus(run.runId(), RunStatus.RUNNING);

    FakeProcess firstSurvivor = new FakeProcess(778L);
    FakeProcess secondSurvivor = new FakeProcess(779L);
    CountDownLatch secondIncidentRegistered = new CountDownLatch(1);
    launcher.terminationFailures.add(new ProcessTerminationException(firstSurvivor, List.of()));
    // awaitCompletion's interrupt path performs its own termination attempt; that exception is the
    // unexpected failure which sends executeRun into its cleanup block.
    launcher.terminationFailures.add(new ProcessTerminationException(secondSurvivor, List.of()));
    // Cleanup terminates once more through RunService's lifecycle gate. This is the second incident
    // actually registered with the runner; the latch makes that registration deterministic.
    launcher.terminationFailures.add(
        new SignallingProcessTerminationException(secondSurvivor, secondIncidentRegistered));

    Run cancelled = service.cancel(run.runId());
    assertThat(cancelled.status()).isEqualTo(RunStatus.ERROR);
    assertThat(secondIncidentRegistered.await(5, TimeUnit.SECONDS)).isTrue();

    launcher.lastProcess().exitNow(0);
    firstSurvivor.exitNow(0);

    // Give A's reaper several poll intervals. B is still alive, so the runner must remain degraded
    // and expose B's PID rather than accept a new run.
    Thread.sleep(200);
    assertThatThrownBy(() -> service.submit(Environment.PUBLIC, Suite.SMOKE))
        .isInstanceOf(RunnerDegradedException.class)
        .hasMessageContaining("779");

    secondSurvivor.exitNow(0);
    Run recovered = awaitRecoveredSubmit(Environment.PUBLIC, Suite.SMOKE);
    awaitStatus(recovered.runId(), RunStatus.RUNNING);
  }

  private Run awaitRecoveredSubmit(Environment environment, Suite suite)
      throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(5);
    while (Instant.now().isBefore(deadline)) {
      try {
        return service.submit(environment, suite);
      } catch (RunnerDegradedException stillDegraded) {
        Thread.sleep(10);
      }
    }
    throw new AssertionError("Runner never recovered from DEGRADED within the deadline");
  }

  @Test
  void cancelOnAnAlreadyTerminalRunIsANoOp(@TempDir Path eventsDir) throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    service = newService(launcher, eventsDir, 5);
    Run submitted = service.submit(Environment.PUBLIC, Suite.SMOKE);
    awaitStatus(submitted.runId(), RunStatus.RUNNING);
    // The real listener always creates the data file before the marker - an ingestor now rejects a
    // marker with no data file at all as an orphan, so the fake here must match that invariant.
    Files.createFile(eventsDir.resolve(submitted.runId() + ".tests.jsonl"));
    Files.createFile(eventsDir.resolve(submitted.runId() + ".tests.complete"));
    launcher.lastProcess().exitNow(0);
    awaitTerminal(submitted.runId());

    Run result = service.cancel(submitted.runId());

    assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
  }

  @Test
  void submitThrowsWhenTheQueueIsFull(@TempDir Path eventsDir) throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    service = newService(launcher, eventsDir, 1);

    Run occupying = service.submit(Environment.PUBLIC, Suite.SMOKE);
    awaitStatus(occupying.runId(), RunStatus.RUNNING);
    service.submit(Environment.PUBLIC, Suite.API); // fills the single queue slot

    assertThatThrownBy(() -> service.submit(Environment.PUBLIC, Suite.UI))
        .isInstanceOf(RunQueueFullException.class);
  }

  /**
   * Regression test for the review's finding: previously, any unexpected {@link RuntimeException}
   * from a collaborator (here, {@code awaitCompletion}) would escape {@code executeRun} on the
   * executor's worker thread with no terminal transition and no {@code activeRuns} cleanup ever
   * recorded, leaving the run stuck non-terminal forever. It must now be recorded as {@code ERROR}
   * and the process terminated as part of cleanup.
   */
  @Test
  void anUnexpectedFailureDuringAwaitIsRecordedAsErrorInsteadOfLeavingTheRunStuck(
      @TempDir Path eventsDir) throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    launcher.awaitCompletionFailure = new RuntimeException("boom - simulated bug");
    service = newService(launcher, eventsDir, 5);

    Run submitted = service.submit(Environment.PUBLIC, Suite.SMOKE);

    Run finished = awaitTerminal(submitted.runId());

    assertThat(finished.status()).isEqualTo(RunStatus.ERROR);
    assertThat(finished.detail()).contains("Unexpected failure").contains("boom - simulated bug");
    assertThat(launcher.lastProcess().wasDestroyed()).isTrue();
  }

  @Test
  void aFailureBeforeStartingIsStillRecordedAsError(@TempDir Path eventsDir) throws Exception {
    FakeProcessLauncher launcher = new FakeProcessLauncher();
    RunLifecycleStore failFirstStore = new FailFirstTransitionStore(new FakeRunLifecycleStore());
    service = newService(failFirstStore, launcher, eventsDir, 5);

    Run submitted = service.submit(Environment.PUBLIC, Suite.SMOKE);

    Run finished = awaitTerminal(submitted.runId());
    assertThat(finished.status()).isEqualTo(RunStatus.ERROR);
    assertThat(finished.startedAt()).isNull();
    assertThat(finished.detail()).contains("simulated transition failure");
  }

  @Test
  void findThrowsForAnUnknownRunId(@TempDir Path eventsDir) {
    service = newService(new FakeProcessLauncher(), eventsDir, 5);

    assertThatThrownBy(() -> service.find("missing")).isInstanceOf(RunNotFoundException.class);
  }

  private RunService newService(ProcessLauncher launcher, Path eventsDir, int queueCapacity) {
    return newService(
        new FakeRunLifecycleStore(), launcher, eventsDir, queueCapacity, Duration.ofSeconds(30));
  }

  private RunService newServiceWithDiskUsage(
      ProcessLauncher launcher,
      Path eventsDir,
      int queueCapacity,
      DiskUsageService diskUsageService) {
    return newServiceWithPolicyAndDiskUsage(
        new FakeRunLifecycleStore(),
        launcher,
        eventsDir,
        queueCapacity,
        Duration.ofSeconds(30),
        RunAvailabilityPolicy.localDev(),
        diskUsageService);
  }

  private RunService newService(
      RunLifecycleStore lifecycleStore,
      ProcessLauncher launcher,
      Path eventsDir,
      int queueCapacity) {
    return newService(lifecycleStore, launcher, eventsDir, queueCapacity, Duration.ofSeconds(30));
  }

  private RunService newService(
      RunLifecycleStore lifecycleStore,
      ProcessLauncher launcher,
      Path eventsDir,
      int queueCapacity,
      Duration processTimeout) {
    return newServiceWithPolicy(
        lifecycleStore,
        launcher,
        eventsDir,
        queueCapacity,
        processTimeout,
        RunAvailabilityPolicy.localDev());
  }

  /**
   * Overload used by tests that need a non-default {@link RunAvailabilityPolicy} (e.g. PORTFOLIO).
   */
  private RunService newServiceWithPolicy(
      RunLifecycleStore lifecycleStore,
      ProcessLauncher launcher,
      Path eventsDir,
      int queueCapacity,
      RunAvailabilityPolicy policy) {
    return newServiceWithPolicy(
        lifecycleStore, launcher, eventsDir, queueCapacity, Duration.ofSeconds(30), policy);
  }

  private RunService newServiceWithPolicy(
      RunLifecycleStore lifecycleStore,
      ProcessLauncher launcher,
      Path eventsDir,
      int queueCapacity,
      Duration processTimeout,
      RunAvailabilityPolicy policy) {
    return newServiceWithPolicyAndDiskUsage(
        lifecycleStore,
        launcher,
        eventsDir,
        queueCapacity,
        processTimeout,
        policy,
        alwaysAvailableDiskUsageService());
  }

  private RunService newServiceWithPolicyAndDiskUsage(
      RunLifecycleStore lifecycleStore,
      ProcessLauncher launcher,
      Path eventsDir,
      int queueCapacity,
      Duration processTimeout,
      RunAvailabilityPolicy policy,
      DiskUsageService diskUsageService) {
    RunnerProperties properties =
        new RunnerProperties(
            ".",
            processTimeout,
            eventsDir.toString(),
            eventsDir.resolve("logs").toString(),
            "src/test/resources/catalog/public-test-catalog.json",
            eventsDir.resolve("artifacts").toString(),
            1024 * 1024,
            Duration.ofMillis(50),
            Duration.ofMillis(20),
            queueCapacity,
            Duration.ofMillis(20),
            Duration.ofSeconds(2),
            10_000,
            Duration.ofSeconds(15),
            Duration.ofMinutes(10),
            new RateLimitRule(5, Duration.ofMinutes(1)),
            new RateLimitRule(10, Duration.ofMinutes(1)),
            new RateLimitRule(3, Duration.ofMinutes(1)),
            new RateLimitRule(10, Duration.ofHours(1)),
            new RateLimitRule(10, Duration.ofMinutes(1)),
            new RateLimitRule(120, Duration.ofMinutes(1)),
            new RateLimitRule(30, Duration.ofMinutes(1)),
            3,
            16384,
            Duration.ofDays(30),
            500,
            Duration.ofDays(14),
            Duration.ofHours(1),
            new RateLimitRule(10, Duration.ofHours(1)),
            1_048_576L,
            26_214_400L,
            209_715_200L,
            2_097_152L,
            2_097_152L,
            104_857_600L,
            new RateLimitRule(10, Duration.ofHours(1)));
    RunEventBroker broker =
        new RunEventBroker(lifecycleStore, properties, noopArtifactIngestionService(properties));
    RunLifecycleCoordinator lifecycle =
        new RunLifecycleCoordinator(broker, noopArtifactIngestionService(properties));
    ListenerEventIngestorFactory ingestorFactory =
        new ListenerEventIngestorFactory(broker, OBJECT_MAPPER, properties);
    if (lifecycleStore instanceof FakeRunLifecycleStore fake) {
      this.store = fake;
    }
    return new RunService(
        lifecycleStore,
        lifecycle,
        recoveryAlreadyComplete(lifecycleStore, lifecycle),
        diskUsageService,
        launcher,
        ingestorFactory,
        new TestCatalogService(properties, OBJECT_MAPPER),
        policy,
        properties);
  }

  private RunService newServiceWithCatalog(
      ProcessLauncher launcher, Path eventsDir, Path catalogFile, int queueCapacity) {
    return newServiceWithCatalog(
        new FakeRunLifecycleStore(), launcher, eventsDir, catalogFile, queueCapacity);
  }

  private RunService newServiceWithCatalog(
      FakeRunLifecycleStore fakeStore,
      ProcessLauncher launcher,
      Path eventsDir,
      Path catalogFile,
      int queueCapacity) {
    store = fakeStore;
    RunnerProperties properties =
        new RunnerProperties(
            ".",
            Duration.ofSeconds(30),
            eventsDir.toString(),
            eventsDir.resolve("logs").toString(),
            catalogFile.toString(),
            eventsDir.resolve("artifacts").toString(),
            1024 * 1024,
            Duration.ofMillis(50),
            Duration.ofMillis(20),
            queueCapacity,
            Duration.ofMillis(20),
            Duration.ofSeconds(2),
            10_000,
            Duration.ofSeconds(15),
            Duration.ofMinutes(10),
            new RateLimitRule(5, Duration.ofMinutes(1)),
            new RateLimitRule(10, Duration.ofMinutes(1)),
            new RateLimitRule(3, Duration.ofMinutes(1)),
            new RateLimitRule(10, Duration.ofHours(1)),
            new RateLimitRule(10, Duration.ofMinutes(1)),
            new RateLimitRule(120, Duration.ofMinutes(1)),
            new RateLimitRule(30, Duration.ofMinutes(1)),
            3,
            16384,
            Duration.ofDays(30),
            500,
            Duration.ofDays(14),
            Duration.ofHours(1),
            new RateLimitRule(10, Duration.ofHours(1)),
            1_048_576L,
            26_214_400L,
            209_715_200L,
            2_097_152L,
            2_097_152L,
            104_857_600L,
            new RateLimitRule(10, Duration.ofHours(1)));
    RunEventBroker broker =
        new RunEventBroker(fakeStore, properties, noopArtifactIngestionService(properties));
    RunLifecycleCoordinator lifecycle =
        new RunLifecycleCoordinator(broker, noopArtifactIngestionService(properties));
    ListenerEventIngestorFactory ingestorFactory =
        new ListenerEventIngestorFactory(broker, OBJECT_MAPPER, properties);
    return new RunService(
        fakeStore,
        lifecycle,
        recoveryAlreadyComplete(fakeStore, lifecycle),
        alwaysAvailableDiskUsageService(),
        launcher,
        ingestorFactory,
        new TestCatalogService(properties, OBJECT_MAPPER),
        RunAvailabilityPolicy.localDev(),
        properties);
  }

  private Run awaitStatus(String runId, RunStatus expected) throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(5);
    Run last;
    do {
      last = service.find(runId);
      if (last.status() == expected) {
        return last;
      }
      Thread.sleep(10);
    } while (Instant.now().isBefore(deadline));
    throw new AssertionError(
        "Run " + runId + " did not reach " + expected + "; last status=" + last.status());
  }

  private Run awaitTerminal(String runId) throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(5);
    Run last;
    do {
      last = service.find(runId);
      if (last.status().isTerminal()) {
        return last;
      }
      Thread.sleep(10);
    } while (Instant.now().isBefore(deadline));
    throw new AssertionError(
        "Run " + runId + " did not reach a terminal status; last status=" + last.status());
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  /** Hangs (like a real long-running Gradle process) until {@link #exitNow} or {@code destroy}. */
  private static final class FakeProcess extends Process {

    static final long FIXED_PID = 777L;

    private final CountDownLatch destroyed = new CountDownLatch(1);
    private final long pid;
    private volatile int exitCode = 137;

    private FakeProcess() {
      this(FIXED_PID);
    }

    private FakeProcess(long pid) {
      this.pid = pid;
    }

    void exitNow(int code) {
      exitCode = code;
      destroyed.countDown();
    }

    boolean wasDestroyed() {
      return destroyed.getCount() == 0;
    }

    boolean awaitDestroyedWithin(Duration timeout) {
      try {
        return destroyed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return false;
      }
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() throws InterruptedException {
      destroyed.await();
      return exitCode;
    }

    @Override
    public int exitValue() {
      if (destroyed.getCount() != 0) {
        throw new IllegalThreadStateException("process has not exited");
      }
      return exitCode;
    }

    @Override
    public void destroy() {
      destroyed.countDown();
    }

    @Override
    public boolean isAlive() {
      return destroyed.getCount() != 0;
    }

    @Override
    public long pid() {
      return pid;
    }
  }

  private static final class SignallingProcessTerminationException
      extends ProcessTerminationException {

    private final CountDownLatch registered;

    private SignallingProcessTerminationException(Process survivor, CountDownLatch registered) {
      super(survivor, List.of());
      this.registered = registered;
    }

    @Override
    public List<Long> survivingPids() {
      registered.countDown();
      return super.survivingPids();
    }
  }

  /** Fake {@link ProcessLauncher} that mirrors GradleProcessRunner's wait/kill-on-timeout shape. */
  private static final class FakeProcessLauncher implements ProcessLauncher {

    private final List<List<String>> startedCommands = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> startedEnvironments = new CopyOnWriteArrayList<>();
    private volatile boolean failToStart;
    private volatile boolean blockProcessStart;
    private volatile boolean failTermination;
    private volatile Runnable terminateBlocker;
    private volatile RuntimeException awaitCompletionFailure;
    private volatile FakeProcess lastProcess;
    private final Queue<ProcessTerminationException> terminationFailures =
        new ConcurrentLinkedQueue<>();
    private final CountDownLatch startEntered = new CountDownLatch(1);
    private final CountDownLatch allowProcessStart = new CountDownLatch(1);
    private final CountDownLatch awaitCompletionFinished = new CountDownLatch(1);

    @Override
    public Process start(
        List<String> command,
        Path workingDirectory,
        Path outputFile,
        Map<String, String> environment)
        throws IOException {
      if (failToStart) {
        throw new IOException("simulated startup failure");
      }
      if (blockProcessStart) {
        startEntered.countDown();
        try {
          allowProcessStart.await();
        } catch (InterruptedException exception) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted while simulating process startup", exception);
        }
      }
      Files.createDirectories(outputFile.getParent());
      Files.writeString(outputFile, "simulated process output");
      startedCommands.add(command);
      startedEnvironments.add(environment);
      FakeProcess process = new FakeProcess();
      lastProcess = process;
      return process;
    }

    @Override
    public ProcessOutcome awaitCompletion(Process process, Duration timeout) {
      try {
        if (awaitCompletionFailure != null) {
          throw awaitCompletionFailure;
        }
        FakeProcess fake = (FakeProcess) process;
        boolean exitedInTime = fake.awaitDestroyedWithin(timeout);
        if (!exitedInTime) {
          // Mirrors GradleProcessRunner.awaitCompletion: on timeout/interrupt it re-attempts
          // termination rather than assuming the process is now dead, so a genuinely unkillable
          // process (failTermination) is modelled the same way here, not just destroyed outright.
          terminate(process);
          return ProcessOutcome.timedOut();
        }
        return ProcessOutcome.completed(fake.exitValue());
      } finally {
        awaitCompletionFinished.countDown();
      }
    }

    @Override
    public void terminate(Process process) {
      if (terminateBlocker != null) {
        terminateBlocker.run();
      }
      ProcessTerminationException queuedFailure = terminationFailures.poll();
      if (queuedFailure != null) {
        throw queuedFailure;
      }
      if (failTermination) {
        throw new ProcessTerminationException(process, List.of());
      }
      ((FakeProcess) process).destroy();
    }

    FakeProcess lastProcess() {
      return lastProcess;
    }
  }
}
