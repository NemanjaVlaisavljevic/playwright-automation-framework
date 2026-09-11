package dev.vlaisanem.automation.runner.service.retention;

import dev.vlaisanem.automation.runner.service.artifacts.ArtifactRepository;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.logging.MdcScope;
import dev.vlaisanem.automation.runner.service.metrics.RunnerMetrics;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The single orchestration point for both retention protocols: full-run cleanup (a terminal run's
 * whole row/files removed past the age or count bound) and artifact-only purge (just its artifact
 * files/metadata, on a shorter window). {@link #sweep} is the one entry point both {@code
 * RetentionScheduler} and {@code RetentionController} call.
 *
 * <p>Every candidate is processed independently - one run's failure (permissions, transient disk
 * issue) must never block every other eligible run or crash the application. A failure means "still
 * tombstoned/claimed, retried next sweep," never a half-deleted state, since the row is deleted
 * only after every file is confirmed gone.
 *
 * <p>{@link RunLifecycleStore#claimForCleanup}/{@code claimForArtifactPurge} alone can't stop two
 * overlapping sweeps (a scheduled tick racing a manual trigger) from double-processing the same
 * run, since a run tombstoned by an in-progress sweep looks identical to one left by an earlier
 * crash. {@link #sweep} also holds an in-process {@link ReentrantLock} (non-blocking {@code
 * tryLock}) for a real sweep's whole duration, so a concurrent call returns immediately with {@link
 * RetentionReport#skipped()} instead of double-processing - sufficient only because this service is
 * single-instance (see README "Known limitations"). The per-run claim guard still matters
 * separately: it lets a sweep resume a run tombstoned by an earlier, now-gone crashed process.
 *
 * <p>A run eligible for both protocols in the same sweep is only ever fully cleaned up, never also
 * artifact-purged in that pass.
 *
 * <p>Every filesystem path here comes only from the configured root directories plus a {@code
 * runId} re-validated against the UUID shape {@code RunService#submit} generates ({@link
 * #requireValidRunId}) - never from the {@code artifacts} table's {@code relative_path} column.
 * Recursive deletion never follows symlinks, so one planted inside a run's directory is deleted
 * itself, never used to escape it.
 */
@Component
public class RetentionService {

  private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

  private static final Pattern VALID_RUN_ID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  /** Sentinel returned by {@link #cleanupRun}/{@link #purgeArtifacts} for a lost claim race. */
  private static final long CLAIM_LOST = -1;

  private final RunLifecycleStore runStore;
  private final ArtifactRepository artifactRepository;
  private final RunnerProperties properties;
  private final RunnerMetrics metrics;
  private final Path artifactsDir;
  private final Path logsDir;
  private final Path rawEventsDir;

  /** The in-process concurrency guard from this class's Javadoc - held only for a real sweep. */
  private final ReentrantLock sweepLock = new ReentrantLock();

  public RetentionService(
      RunLifecycleStore runStore,
      ArtifactRepository artifactRepository,
      RunnerProperties properties,
      RunnerMetrics metrics) {
    this.runStore = runStore;
    this.artifactRepository = artifactRepository;
    this.properties = properties;
    this.metrics = metrics;
    this.artifactsDir = Path.of(properties.artifactsDir()).toAbsolutePath().normalize();
    this.logsDir = Path.of(properties.logsDir()).toAbsolutePath().normalize();
    this.rawEventsDir = Path.of(properties.rawEventsDir()).toAbsolutePath().normalize();
  }

  public RetentionReport sweep(boolean dryRun) {
    if (dryRun) {
      Candidates candidates = computeCandidates();
      return new RetentionReport(
          true,
          candidates.fullCleanupIds.size(),
          0,
          0,
          candidates.artifactPurgeIds.size(),
          0,
          0,
          0,
          false);
    }

    // Non-blocking: a second concurrent real sweep must never wait for, or race, the one already
    // running.
    if (!sweepLock.tryLock()) {
      log.info(
          "Retention sweep skipped - another real sweep is already running in this process; the"
              + " next scheduled tick or manual trigger will pick up whatever is still eligible");
      return new RetentionReport(false, 0, 0, 0, 0, 0, 0, 0, true);
    }
    try {
      RetentionReport report = runRealSweep();
      metrics.recordRetention(report);
      return report;
    } catch (RuntimeException wholeSweepFailure) {
      metrics.recordRetentionSweepFailure();
      throw wholeSweepFailure;
    } finally {
      sweepLock.unlock();
    }
  }

  private RetentionReport runRealSweep() {
    Candidates candidates = computeCandidates();

    int runDeleted = 0;
    int runFailed = 0;
    long bytesFreed = 0;
    for (String runId : candidates.fullCleanupIds) {
      try {
        // Wrapped so any logging cleanupRun itself performs also carries runId as a real MDC
        // field, not only the structured addKeyValue below on failure.
        long freed =
            MdcScope.withMdc(
                "runId", runId, () -> cleanupRun(runId, candidates.pendingCleanup.contains(runId)));
        if (freed != CLAIM_LOST) {
          runDeleted++;
          bytesFreed += freed;
        }
      } catch (RuntimeException e) {
        log.atError()
            .addKeyValue("runId", runId)
            .setCause(e)
            .log("Retention: full cleanup failed - will retry next sweep");
        runFailed++;
      }
    }

    int purgeCompleted = 0;
    int purgeFailed = 0;
    for (String runId : candidates.artifactPurgeIds) {
      try {
        long freed =
            MdcScope.withMdc(
                "runId",
                runId,
                () -> purgeArtifacts(runId, candidates.pendingPurge.contains(runId)));
        if (freed != CLAIM_LOST) {
          purgeCompleted++;
          bytesFreed += freed;
        }
      } catch (RuntimeException e) {
        log.atError()
            .addKeyValue("runId", runId)
            .setCause(e)
            .log("Retention: artifact purge failed - will retry next sweep");
        purgeFailed++;
      }
    }

    return new RetentionReport(
        false,
        candidates.fullCleanupIds.size(),
        runDeleted,
        runFailed,
        candidates.artifactPurgeIds.size(),
        purgeCompleted,
        purgeFailed,
        bytesFreed,
        false);
  }

  /**
   * Kept separate from the newly-eligible set: an already-tombstoned/claimed run must never go
   * through {@code claimForCleanup}/{@code claimForArtifactPurge} again, since a resumed run
   * (claimed by an earlier, possibly crashed, attempt) would always lose that race and silently
   * skip the resume. See {@code cleanupRun}/{@code purgeArtifacts}'s {@code alreadyClaimed} param.
   */
  private Candidates computeCandidates() {
    Instant now = Instant.now();

    Set<String> pendingCleanup = new LinkedHashSet<>(runStore.findPendingCleanup());
    Set<String> newlyEligibleCleanup =
        new LinkedHashSet<>(
            runStore.findEligibleForCleanup(
                now,
                properties.retentionRunHistoryMaxAge(),
                properties.retentionRunHistoryMaxCount()));
    Set<String> fullCleanupIds = new LinkedHashSet<>(pendingCleanup);
    fullCleanupIds.addAll(newlyEligibleCleanup);

    Set<String> pendingPurge = new LinkedHashSet<>(runStore.findPendingArtifactPurge());
    Set<String> newlyEligiblePurge =
        new LinkedHashSet<>(
            runStore.findEligibleForArtifactPurge(now, properties.retentionArtifactMaxAge()));
    Set<String> artifactPurgeIds = new LinkedHashSet<>(pendingPurge);
    artifactPurgeIds.addAll(newlyEligiblePurge);
    // Precedence: full-run cleanup always wins - a run qualifying for both in the same sweep is
    // simply fully deleted, never also (redundantly) artifact-purged in the same pass.
    artifactPurgeIds.removeAll(fullCleanupIds);

    return new Candidates(pendingCleanup, fullCleanupIds, pendingPurge, artifactPurgeIds);
  }

  private record Candidates(
      Set<String> pendingCleanup,
      Set<String> fullCleanupIds,
      Set<String> pendingPurge,
      Set<String> artifactPurgeIds) {}

  /**
   * Validate, claim (unless {@code alreadyClaimed}), delete every on-disk file, then delete the row
   * - in that exact order. Validating the {@code runId} shape before claiming means a malformed id
   * is never tombstoned, so it stays visible and simply fails on every retry rather than being
   * hidden behind an unfinishable tombstone. A crash after the claim always leaves a
   * tombstoned-but-not-fully-gone run for the next sweep to safely resume.
   *
   * @param alreadyClaimed {@code true} for a run resumed from {@link
   *     RunLifecycleStore#findPendingCleanup} - already tombstoned by an earlier attempt, so {@link
   *     RunLifecycleStore#claimForCleanup} must not be called again (it would always lose that
   *     race). File/row deletion is idempotent regardless.
   */
  private long cleanupRun(String runId, boolean alreadyClaimed) {
    String safeRunId = requireValidRunId(runId);
    if (!alreadyClaimed && !runStore.claimForCleanup(runId)) {
      return CLAIM_LOST;
    }
    long freed = deleteRunDirectoryIfPresent(safeRunId);
    deleteIfExists(logsDir.resolve(safeRunId + ".log"));
    deleteIfExists(rawEventsDir.resolve(safeRunId + ".tests.jsonl"));
    deleteIfExists(rawEventsDir.resolve(safeRunId + ".tests.complete"));
    // A run whose raw event stream overflowed its size cap gets this marker instead of
    // .tests.complete; it must be cleaned up here too or it would linger forever.
    deleteIfExists(rawEventsDir.resolve(safeRunId + ".tests.overflow"));
    runStore.deleteRun(runId);
    return freed;
  }

  /**
   * Validate, claim (unless {@code alreadyClaimed}), delete the artifact directory, then complete
   * the purge (delete rows + set purged_at) - see {@link #cleanupRun} for the same ordering
   * rationale and what {@code alreadyClaimed} means.
   */
  private long purgeArtifacts(String runId, boolean alreadyClaimed) {
    String safeRunId = requireValidRunId(runId);
    if (!alreadyClaimed && !runStore.claimForArtifactPurge(runId)) {
      return CLAIM_LOST;
    }
    long freed = deleteRunDirectoryIfPresent(safeRunId);
    artifactRepository.completePurge(runId);
    return freed;
  }

  private long deleteRunDirectoryIfPresent(String safeRunId) {
    Path dir = artifactsDir.resolve(safeRunId);
    if (!Files.exists(dir)) {
      return 0;
    }
    long size = directorySize(dir);
    deleteRecursively(dir);
    return size;
  }

  private static String requireValidRunId(String runId) {
    if (runId == null || !VALID_RUN_ID.matcher(runId).matches()) {
      throw new IllegalArgumentException(
          "Refusing to build a filesystem path from a runId that isn't the exact UUID shape"
              + " RunService#submit always generates: "
              + runId);
    }
    return runId;
  }

  private static long directorySize(Path dir) {
    try (var walk = Files.walk(dir)) {
      return walk.filter(Files::isRegularFile).mapToLong(RetentionService::sizeOf).sum();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static long sizeOf(Path file) {
    try {
      return Files.size(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteRecursively(Path root) {
    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.deleteIfExists(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                throws IOException {
              Files.deleteIfExists(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteIfExists(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
