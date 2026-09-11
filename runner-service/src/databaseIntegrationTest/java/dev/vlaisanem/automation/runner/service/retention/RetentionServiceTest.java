package dev.vlaisanem.automation.runner.service.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.contract.ArtifactType;
import dev.vlaisanem.automation.runner.contract.RunOutcome;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.artifacts.jdbc.JdbcArtifactRepository;
import dev.vlaisanem.automation.runner.service.config.RateLimitRule;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.metrics.RunnerMetrics;
import dev.vlaisanem.automation.runner.service.repository.CommittedRunChange;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import dev.vlaisanem.automation.runner.service.repository.jdbc.JdbcRunStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies {@link RetentionService} against real Postgres and a real temp filesystem. A fresh
 * container per test method (unlike most {@code databaseIntegrationTest} classes, which share one)
 * - exact candidate/deleted counts would be fragile against an accumulating shared schema.
 */
@Testcontainers
class RetentionServiceTest {

  @Container
  private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @TempDir private Path tempDir;

  private JdbcTemplate jdbcTemplate;
  private JdbcRunStore runStore;
  private JdbcArtifactRepository artifactRepository;
  private RetentionService retentionService;
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final RunnerMetrics metrics = new RunnerMetrics(meterRegistry);
  private Path artifactsDir;
  private Path logsDir;
  private Path rawEventsDir;

  /**
   * Kept as a field so a test can open its own extra connection to hold a real, uncommitted {@code
   * SELECT ... FOR UPDATE} lock on a {@code runs} row - the only way to force a genuine
   * concurrent-transaction interleaving.
   */
  private PGSimpleDataSource dataSource;

  @BeforeEach
  void setUp() throws IOException {
    String jdbcUrl = postgres.getJdbcUrl();
    Flyway.configure()
        .dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword())
        .load()
        .migrate();

    dataSource = new PGSimpleDataSource();
    dataSource.setUrl(jdbcUrl);
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());

    ObjectMapper objectMapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    jdbcTemplate = new JdbcTemplate(dataSource);
    TransactionTemplate transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    runStore = new JdbcRunStore(jdbcTemplate, transactionTemplate, objectMapper);
    artifactRepository = new JdbcArtifactRepository(jdbcTemplate, transactionTemplate);

    artifactsDir = Files.createDirectories(tempDir.resolve("artifacts"));
    logsDir = Files.createDirectories(tempDir.resolve("logs"));
    rawEventsDir = Files.createDirectories(tempDir.resolve("raw"));

    RunnerProperties properties =
        testProperties(
            artifactsDir, logsDir, rawEventsDir, Duration.ofDays(30), 500, Duration.ofDays(14));
    retentionService = new RetentionService(runStore, artifactRepository, properties, metrics);
  }

  @Test
  void crashAfterTombstoneIsResumedByTheNextSweep() throws IOException {
    Instant now = Instant.parse("2026-06-01T00:00:00Z");
    String runId = seedTerminalRun(now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(31)));
    Path runDir = seedRunFiles(runId);

    // Simulate a crash right after the tombstone commits, before any file is touched.
    assertThat(runStore.claimForCleanup(runId)).isTrue();
    assertThat(Files.exists(runDir)).as("nothing deleted yet").isTrue();
    assertThat(runStore.findPendingCleanup()).contains(runId);

    RetentionReport report = retentionService.sweep(false);

    assertThat(report.runDeletedCount()).isEqualTo(1);
    assertThat(Files.exists(runDir)).isFalse();
    assertThat(rawRunExists(runId)).isFalse();
  }

  /**
   * A run whose raw event stream overflowed gets a {@code .tests.overflow} marker instead of {@code
   * .tests.complete}; cleanup must remove it too or it lingers forever.
   */
  @Test
  void cleansUpAnOverflowMarkerAlongsideEveryOtherRawEventFile() throws IOException {
    Instant now = Instant.parse("2026-06-01T00:00:00Z");
    String runId = seedTerminalRun(now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(31)));
    seedRunFiles(runId);
    Path overflowMarker = rawEventsDir.resolve(runId + ".tests.overflow");
    Files.writeString(overflowMarker, "");

    RetentionReport report = retentionService.sweep(false);

    assertThat(report.runDeletedCount()).isEqualTo(1);
    assertThat(Files.exists(overflowMarker)).isFalse();
  }

  @Test
  void crashAfterFileDeletionBeforeDbDeleteIsResumedByTheNextSweep() throws IOException {
    Instant now = Instant.parse("2026-06-01T00:00:00Z");
    String runId = seedTerminalRun(now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(31)));
    Path runDir = seedRunFiles(runId);

    // Simulate a crash after every file was deleted but before the DB row was - claim directly,
    // then delete the files ourselves (bypassing RetentionService), leaving the tombstone+row.
    assertThat(runStore.claimForCleanup(runId)).isTrue();
    deleteRecursivelyForTest(runDir);
    assertThat(rawRunExists(runId)).as("row must still exist, only its files are gone").isTrue();

    RetentionReport report = retentionService.sweep(false);

    assertThat(report.runFailedCount()).isZero();
    assertThat(report.runDeletedCount()).isEqualTo(1);
    assertThat(rawRunExists(runId)).isFalse();
  }

  @Test
  void twoConcurrentSweepsNeverDoubleProcessTheSameRun() throws Exception {
    Instant now = Instant.parse("2026-06-01T00:00:00Z");
    List<String> runIds =
        List.of(
            seedTerminalRun(now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(31))),
            seedTerminalRun(now.minus(Duration.ofDays(41)), now.minus(Duration.ofDays(32))),
            seedTerminalRun(now.minus(Duration.ofDays(42)), now.minus(Duration.ofDays(33))));
    for (String runId : runIds) {
      seedRunFiles(runId);
    }

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<RetentionReport> first = executor.submit(() -> retentionService.sweep(false));
      Future<RetentionReport> second = executor.submit(() -> retentionService.sweep(false));
      RetentionReport reportA = first.get(30, TimeUnit.SECONDS);
      RetentionReport reportB = second.get(30, TimeUnit.SECONDS);

      assertThat(reportA.runDeletedCount() + reportB.runDeletedCount())
          .as("every run must be fully processed exactly once across both concurrent sweeps")
          .isEqualTo(runIds.size());
      assertThat(reportA.runFailedCount() + reportB.runFailedCount()).isZero();
      for (String runId : runIds) {
        assertThat(rawRunExists(runId)).isFalse();
      }
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Deterministically forces the interleaving the test above only races: a second sweep starting
   * while the first has tombstoned a run but not yet deleted its files. It must be skipped
   * entirely, never racing the first sweep's in-flight deletion.
   */
  @Test
  void aConcurrentSweepWhileAnotherIsMidCleanupIsSkippedNotDoubleProcessed() throws Exception {
    Instant now = Instant.parse("2026-06-01T00:00:00Z");
    String runId = seedTerminalRun(now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(31)));
    Path runDir = seedRunFiles(runId);

    BlockingAfterClaimRunStore blockingStore = new BlockingAfterClaimRunStore(runStore);
    RetentionService blockingRetentionService =
        new RetentionService(
            blockingStore,
            artifactRepository,
            testProperties(
                artifactsDir, logsDir, rawEventsDir, Duration.ofDays(30), 500, Duration.ofDays(14)),
            metrics);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<RetentionReport> first = executor.submit(() -> blockingRetentionService.sweep(false));
      assertThat(blockingStore.awaitClaimed(5, TimeUnit.SECONDS))
          .as("the first sweep must reach the post-claim pause before this test proceeds")
          .isTrue();
      assertThat(Files.exists(runDir)).as("nothing deleted yet - still paused").isTrue();

      RetentionReport second = blockingRetentionService.sweep(false);
      assertThat(second.skipped())
          .as("a second sweep call while the first is mid-cleanup must be skipped, not race it")
          .isTrue();
      assertThat(second.runCandidateCount()).isZero();
      assertThat(second.runDeletedCount()).isZero();

      blockingStore.release();
      RetentionReport firstResult = first.get(5, TimeUnit.SECONDS);
      assertThat(firstResult.skipped()).isFalse();
      assertThat(firstResult.runDeletedCount()).isEqualTo(1);
      assertThat(Files.exists(runDir)).isFalse();
      assertThat(rawRunExists(runId)).isFalse();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void ingestVsPurgeRaceNeverResurrectsMetadataAndLeavesNoDanglingRows() {
    Instant now = Instant.parse("2026-06-01T00:00:00Z");
    String runId = seedTerminalRun(now.minus(Duration.ofDays(20)), now.minus(Duration.ofDays(16)));
    ingestOneArtifact(runId, "artifact-1", now.minus(Duration.ofDays(16)));
    assertThat(countArtifactRows(runId)).isEqualTo(1);

    // Claims the purge directly, simulating the exact window a concurrent ingestion retry could
    // land in - findForRun/isArtifactsPurged must reflect "no longer available" the instant purge
    // is claimed, not only once completePurge finishes.
    assertThat(runStore.claimForArtifactPurge(runId)).isTrue();
    assertThat(artifactRepository.findForRun(runId, null))
        .as("the list must go empty the instant purge is claimed, not only once complete")
        .isEmpty();
    assertThat(artifactRepository.isArtifactsPurged(runId)).isTrue();

    // Attempt to (re-)ingest for this same run - the per-run row lock inside
    // JdbcArtifactRepository#ingest must skip it, never resurrect the metadata row.
    artifactRepository.ingest(List.of(entry(runId, "artifact-2", now.minus(Duration.ofDays(16)))));
    assertThat(countArtifactRows(runId))
        .as("the racing ingest for artifact-2 must have been silently skipped, not inserted")
        .isEqualTo(1);

    artifactRepository.completePurge(runId);

    assertThat(countArtifactRows(runId))
        .as("no artifact metadata may linger behind the now-deleted files")
        .isZero();
    Boolean purgedAtSet =
        jdbcTemplate.queryForObject(
            "SELECT artifacts_purged_at IS NOT NULL FROM runs WHERE run_id = ?",
            Boolean.class,
            runId);
    assertThat(purgedAtSet).isTrue();

    // A further ingest attempt after the purge has fully completed must also be skipped.
    artifactRepository.ingest(List.of(entry(runId, "artifact-3", now.minus(Duration.ofDays(16)))));
    assertThat(countArtifactRows(runId)).isZero();
  }

  @Test
  void concurrentIngestIsBlockedThenSkippedWhenPurgeAlreadyHoldsThePerRunLock() throws Exception {
    Instant now = Instant.parse("2026-06-01T00:00:00Z");
    String runId = seedTerminalRun(now.minus(Duration.ofDays(20)), now.minus(Duration.ofDays(16)));

    // Simulates purge's own claim, holding the per-run row lock open via a real second
    // connection - proves the actual Postgres lock, not merely an assumption about it.
    try (Connection lockingConnection = dataSource.getConnection()) {
      lockingConnection.setAutoCommit(false);
      try (var claim =
          lockingConnection.prepareStatement(
              "UPDATE runs SET artifacts_purge_started_at = now() WHERE run_id = ?"
                  + " AND artifacts_purge_started_at IS NULL")) {
        claim.setString(1, runId);
        assertThat(claim.executeUpdate()).isEqualTo(1);
      }

      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        Future<Void> ingestFuture =
            executor.submit(
                () -> {
                  artifactRepository.ingest(
                      List.of(entry(runId, "artifact-1", now.minus(Duration.ofDays(16)))));
                  return null;
                });

        assertThatThrownBy(() -> ingestFuture.get(300, TimeUnit.MILLISECONDS))
            .as("ingest must genuinely block on the still-open, uncommitted purge-claim lock")
            .isInstanceOf(java.util.concurrent.TimeoutException.class);

        lockingConnection.commit();

        ingestFuture.get(5, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
      }
    }

    assertThat(countArtifactRows(runId))
        .as("ingest must have seen the now-committed purge flag and skipped, not inserted")
        .isZero();
  }

  @Test
  void concurrentArtifactPurgeClaimIsBlockedThenSucceedsWhenIngestAlreadyHoldsThePerRunLock()
      throws Exception {
    Instant now = Instant.parse("2026-06-01T00:00:00Z");
    String runId = seedTerminalRun(now.minus(Duration.ofDays(20)), now.minus(Duration.ofDays(16)));

    // Simulates ingest's own per-run lock and row insert, held open via a second raw connection
    // so the test controls exactly when it commits.
    try (Connection lockingConnection = dataSource.getConnection()) {
      lockingConnection.setAutoCommit(false);
      try (var lock =
          lockingConnection.prepareStatement("SELECT * FROM runs WHERE run_id = ? FOR UPDATE")) {
        lock.setString(1, runId);
        lock.executeQuery();
      }
      try (var insert =
          lockingConnection.prepareStatement(
              "INSERT INTO artifacts (artifact_id, run_id, test_id, test_display_name, step_id,"
                  + " schema_version, artifact_type, relative_path, size_bytes, media_type,"
                  + " created_at) VALUES (?, ?, 'some.Test#methodOne', 'Some test', NULL, '1.1',"
                  + " 'SCREENSHOT', 'shot.png', 100, 'image/png', now())")) {
        insert.setString(1, "artifact-1");
        insert.setString(2, runId);
        assertThat(insert.executeUpdate()).isEqualTo(1);
      }

      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        Future<Boolean> claimFuture = executor.submit(() -> runStore.claimForArtifactPurge(runId));

        assertThatThrownBy(() -> claimFuture.get(300, TimeUnit.MILLISECONDS))
            .as("claimForArtifactPurge must genuinely block on ingest's still-open lock")
            .isInstanceOf(java.util.concurrent.TimeoutException.class);

        lockingConnection.commit();

        assertThat(claimFuture.get(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        executor.shutdownNow();
      }
    }

    // The row ingest committed before purge ever claimed must still be found and removed by the
    // purge protocol - never left dangling once the whole sequence finishes.
    artifactRepository.completePurge(runId);
    assertThat(countArtifactRows(runId))
        .as("the row ingest committed first must be swept up by the purge that came after it")
        .isZero();
  }

  @Test
  void refusesToBuildAPathFromARunIdThatIsNotTheExactUuidShape() {
    Instant now = Instant.parse("2026-06-01T00:00:00Z");
    String malformedRunId = "../not-a-real-uuid";
    seedTerminalRunWithExplicitId(
        malformedRunId, now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(31)));

    RetentionReport report = retentionService.sweep(false);

    assertThat(report.runFailedCount())
        .as("a malformed runId must fail loudly, isolated, never crash the whole sweep")
        .isEqualTo(1);
    assertThat(report.runDeletedCount()).isZero();
    assertThat(rawRunExists(malformedRunId))
        .as("never tombstoned at all - validation happens before the claim")
        .isTrue();
  }

  @Test
  void recursiveDeletionNeverFollowsASymlinkOutOfTheRunDirectory() throws IOException {
    Instant now = Instant.parse("2026-06-01T00:00:00Z");
    String runId = seedTerminalRun(now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(31)));
    Path runDir = Files.createDirectories(artifactsDir.resolve(runId));
    Files.writeString(runDir.resolve("real-file.txt"), "real artifact content");

    Path victim = Files.createFile(tempDir.resolve("victim-outside-run-dir.txt"));
    Files.writeString(victim, "must survive");
    Path symlink = runDir.resolve("escape-link");
    try {
      Files.createSymbolicLink(symlink, victim);
    } catch (UnsupportedOperationException | FileSystemException e) {
      Assumptions.abort("Symlinks are not creatable in this environment: " + e);
      return;
    }

    RetentionReport report = retentionService.sweep(false);

    assertThat(report.runDeletedCount()).isEqualTo(1);
    assertThat(Files.exists(runDir)).as("the run's own directory is gone").isFalse();
    assertThat(Files.exists(victim))
        .as("the symlink target outside the run directory must never be touched")
        .isTrue();
    assertThat(Files.readString(victim)).isEqualTo("must survive");
  }

  @Test
  void idempotentRepeatedSweepIsATrueNoOp() {
    Instant now = Instant.parse("2026-06-01T00:00:00Z");
    String runId = seedTerminalRun(now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(31)));
    seedRunFiles(runId);

    RetentionReport firstSweep = retentionService.sweep(false);
    assertThat(firstSweep.runDeletedCount()).isEqualTo(1);

    RetentionReport secondSweep = retentionService.sweep(false);

    assertThat(secondSweep.runCandidateCount()).isZero();
    assertThat(secondSweep.runDeletedCount()).isZero();
    assertThat(secondSweep.runFailedCount()).isZero();
    assertThat(secondSweep.artifactPurgeCandidateCount()).isZero();
  }

  @Test
  void dryRunPreviewTouchesNothingThenARealSweepDeletesExactlyTheEligibleRun() throws IOException {
    Instant now = Instant.parse("2026-06-01T00:00:00Z");
    String eligibleRunId =
        seedTerminalRun(now.minus(Duration.ofDays(40)), now.minus(Duration.ofDays(31)));
    Path eligibleRunDir = seedRunFiles(eligibleRunId);

    String activeRunId = UUID.randomUUID().toString();
    runStore.queue(
        activeRunId,
        Environment.PUBLIC,
        Suite.SMOKE,
        now,
        List.of(),
        seq -> RunnerEvent.runQueued(activeRunId, seq, now));

    RetentionReport preview = retentionService.sweep(true);

    assertThat(preview.dryRun()).isTrue();
    assertThat(preview.runCandidateCount()).isEqualTo(1);
    assertThat(preview.runDeletedCount()).isZero();
    assertThat(Files.exists(eligibleRunDir)).as("dry run must not touch anything").isTrue();
    assertThat(rawRunExists(eligibleRunId)).isTrue();
    // A dry-run preview must never record cleanup metrics - it never claimed or deleted anything.
    assertThat(meterRegistry.find("runner.retention.runs_deleted").counter()).isNull();

    RetentionReport real = retentionService.sweep(false);

    assertThat(real.dryRun()).isFalse();
    assertThat(real.runDeletedCount()).isEqualTo(1);
    assertThat(Files.exists(eligibleRunDir)).isFalse();
    assertThat(rawRunExists(eligibleRunId)).isFalse();
    assertThat(runStore.findById(activeRunId))
        .as("the still-active run must be completely untouched")
        .isPresent();
    assertThat(meterRegistry.find("runner.retention.runs_deleted").counter().count())
        .isEqualTo(1.0);
  }

  private String seedTerminalRun(Instant requestedAt, Instant finishedAt) {
    String runId = UUID.randomUUID().toString();
    seedTerminalRunWithExplicitId(runId, requestedAt, finishedAt);
    return runId;
  }

  private void seedTerminalRunWithExplicitId(
      String runId, Instant requestedAt, Instant finishedAt) {
    runStore.queue(
        runId,
        Environment.PUBLIC,
        Suite.SMOKE,
        requestedAt,
        List.of(),
        seq -> RunnerEvent.runQueued(runId, seq, requestedAt));
    Instant startedAt = requestedAt.plusSeconds(1);
    runStore.transitionIfNonTerminal(
        runId, run -> run.transitionTo(RunStatus.STARTING, startedAt), null);
    runStore.transitionIfNonTerminal(
        runId,
        run -> run.transitionTo(RunStatus.RUNNING, startedAt),
        seq -> RunnerEvent.runStarted(runId, seq, startedAt));
    runStore.transitionIfNonTerminal(
        runId,
        run -> run.transitionTo(RunStatus.SUCCEEDED, finishedAt),
        seq -> RunnerEvent.runFinished(runId, seq, finishedAt, RunOutcome.SUCCEEDED, null));
  }

  /** Creates a realistic on-disk footprint (artifact dir + process log + raw event files). */
  private Path seedRunFiles(String runId) {
    try {
      Path runDir = Files.createDirectories(artifactsDir.resolve(runId));
      Files.writeString(runDir.resolve("screenshot.png"), "fake-image-bytes");
      Files.writeString(logsDir.resolve(runId + ".log"), "fake process log");
      Files.writeString(rawEventsDir.resolve(runId + ".tests.jsonl"), "{}\n");
      Files.writeString(rawEventsDir.resolve(runId + ".tests.complete"), "");
      return runDir;
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private void deleteRecursivelyForTest(Path root) throws IOException {
    try (var walk = Files.walk(root)) {
      walk.sorted(java.util.Comparator.reverseOrder()).forEach(this::deleteQuietly);
    }
  }

  private void deleteQuietly(Path path) {
    try {
      Files.delete(path);
    } catch (IOException ignored) {
      // best-effort test cleanup helper
    }
  }

  private boolean rawRunExists(String runId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM runs WHERE run_id = ?", Integer.class, runId);
    return count != null && count > 0;
  }

  /**
   * Raw row count, bypassing {@link JdbcArtifactRepository#findForRun}'s purge-claim filtering -
   * tests using this want the physical row's existence, not what a client would currently see.
   */
  private int countArtifactRows(String runId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM artifacts WHERE run_id = ?", Integer.class, runId);
    return count == null ? 0 : count;
  }

  private void ingestOneArtifact(String runId, String artifactId, Instant createdAt) {
    artifactRepository.ingest(List.of(entry(runId, artifactId, createdAt)));
  }

  private static ArtifactManifestEntry entry(String runId, String artifactId, Instant createdAt) {
    return new ArtifactManifestEntry(
        "1.1",
        artifactId,
        runId,
        "some.Test#methodOne",
        "Some test",
        null,
        ArtifactType.SCREENSHOT,
        "shot.png",
        "image/png",
        100,
        createdAt);
  }

  private static RunnerProperties testProperties(
      Path artifactsDir,
      Path logsDir,
      Path rawEventsDir,
      Duration retentionRunHistoryMaxAge,
      int retentionRunHistoryMaxCount,
      Duration retentionArtifactMaxAge) {
    RateLimitRule aRule = new RateLimitRule(5, Duration.ofMinutes(1));
    return new RunnerProperties(
        ".",
        Duration.ofSeconds(30),
        rawEventsDir.toString(),
        logsDir.toString(),
        "src/test/resources/catalog/public-test-catalog.json",
        artifactsDir.toString(),
        1024 * 1024,
        Duration.ofSeconds(5),
        Duration.ofSeconds(1),
        1,
        Duration.ofMillis(150),
        Duration.ofSeconds(5),
        10_000,
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
        retentionRunHistoryMaxAge,
        retentionRunHistoryMaxCount,
        retentionArtifactMaxAge,
        Duration.ofHours(1),
        aRule,
        1_048_576L,
        26_214_400L,
        209_715_200L,
        2_097_152L,
        2_097_152L,
        104_857_600L,
        aRule,
        Duration.ofSeconds(60));
  }

  /**
   * Delegates every {@link RunLifecycleStore} method except {@link #claimForCleanup}, which pauses
   * right after the delegate's claim succeeds - forces the post-claim, pre-deletion window {@link
   * #aConcurrentSweepWhileAnotherIsMidCleanupIsSkippedNotDoubleProcessed} needs.
   */
  private static final class BlockingAfterClaimRunStore implements RunLifecycleStore {
    private final RunLifecycleStore delegate;
    private final CountDownLatch claimed = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    private BlockingAfterClaimRunStore(RunLifecycleStore delegate) {
      this.delegate = delegate;
    }

    boolean awaitClaimed(long timeout, TimeUnit unit) throws InterruptedException {
      return claimed.await(timeout, unit);
    }

    void release() {
      release.countDown();
    }

    @Override
    public boolean claimForCleanup(String runId) {
      boolean result = delegate.claimForCleanup(runId);
      if (result) {
        claimed.countDown();
        try {
          release.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      }
      return result;
    }

    @Override
    public CommittedRunChange queue(
        String runId,
        Environment environment,
        Suite suite,
        Instant requestedAt,
        List<SelectedTestSnapshot> selectedTests,
        LongFunction<RunnerEvent> queuedEventFactory) {
      return delegate.queue(
          runId, environment, suite, requestedAt, selectedTests, queuedEventFactory);
    }

    @Override
    public Optional<CommittedRunChange> transitionIfNonTerminal(
        String runId, UnaryOperator<Run> transition, LongFunction<RunnerEvent> eventFactory) {
      return delegate.transitionIfNonTerminal(runId, transition, eventFactory);
    }

    @Override
    public Optional<RunnerEvent> appendEventIfNonTerminal(
        String runId, LongFunction<RunnerEvent> eventFactory) {
      return delegate.appendEventIfNonTerminal(runId, eventFactory);
    }

    @Override
    public Optional<Run> findById(String runId) {
      return delegate.findById(runId);
    }

    @Override
    public List<Run> findAll() {
      return delegate.findAll();
    }

    @Override
    public List<Run> findNonTerminal() {
      return delegate.findNonTerminal();
    }

    @Override
    public List<RunnerEvent> readEventsAfter(String runId, long afterSequence) {
      return delegate.readEventsAfter(runId, afterSequence);
    }

    @Override
    public Optional<RunnerEvent> latestEvent(String runId) {
      return delegate.latestEvent(runId);
    }

    @Override
    public List<String> findEligibleForCleanup(Instant now, Duration maxAge, int maxCount) {
      return delegate.findEligibleForCleanup(now, maxAge, maxCount);
    }

    @Override
    public List<String> findPendingCleanup() {
      return delegate.findPendingCleanup();
    }

    @Override
    public void deleteRun(String runId) {
      delegate.deleteRun(runId);
    }

    @Override
    public List<String> findEligibleForArtifactPurge(Instant now, Duration maxAge) {
      return delegate.findEligibleForArtifactPurge(now, maxAge);
    }

    @Override
    public List<String> findPendingArtifactPurge() {
      return delegate.findPendingArtifactPurge();
    }

    @Override
    public boolean claimForArtifactPurge(String runId) {
      return delegate.claimForArtifactPurge(runId);
    }
  }
}
