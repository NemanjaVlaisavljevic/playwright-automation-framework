package dev.vlaisanem.automation.runner.service.retention;

import dev.vlaisanem.automation.runner.service.artifacts.ArtifactRepository;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
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
 * D4.1 (docs/RELEASE_EVIDENCE.md's D4.1 section) - the single orchestration point for both
 * retention protocols: full-run cleanup (a terminal run's whole row/files removed once it exceeds
 * either the age or count bound - see {@link RunLifecycleStore#findEligibleForCleanup}) and
 * artifact-only purge (a terminal run's artifact files/metadata removed on their own, shorter
 * window - see {@link RunLifecycleStore#findEligibleForArtifactPurge}). {@link #sweep} is the one
 * entry point both {@code RetentionScheduler} and {@code RetentionController} call.
 *
 * <p><strong>Every candidate is processed independently</strong> - unlike {@code
 * RunRecoveryService}'s deliberately fail-closed startup gate, a periodic maintenance sweep must
 * never let one run's own failure (a permissions error, a transient disk issue) block every other
 * eligible run, and must never crash the application over it. A failure here means "still
 * tombstoned/claimed, retried on the next sweep" - never an inconsistent, half-deleted state, since
 * the row is deleted only after every file is confirmed gone (see {@link #cleanupRun}).
 *
 * <p><strong>Concurrency (revised, D4.1 review round)</strong>: {@link
 * RunLifecycleStore#claimForCleanup}/{@link RunLifecycleStore#claimForArtifactPurge} alone are
 * <em>not</em> sufficient to prevent two overlapping sweeps (a scheduled tick racing a manual
 * {@code POST /api/v1/retention/run}) from double-processing the same run - a review finding: a run
 * already tombstoned by an in-progress sweep is indistinguishable, via {@link
 * RunLifecycleStore#findPendingCleanup}, from one left over by an earlier crash, so a second,
 * genuinely concurrent sweep would see {@code alreadyClaimed=true} and race the first sweep's own
 * in-flight file deletion instead of safely skipping it. {@link #sweep} therefore also holds a
 * plain in-process {@link ReentrantLock} for the whole duration of a real (non-dry-run) sweep - a
 * non-blocking {@code tryLock}, so a second concurrent call returns immediately with {@link
 * RetentionReport#skipped()} set, touching nothing, rather than blocking or double-processing. This
 * is correct and sufficient for this project's own documented "single-instance only" scope (see
 * {@code README.md}'s own "Known limitations" bullet - one {@code runner-service} process, no
 * clustering); a genuinely multi-instance deployment would need a real DB-level owner/lease
 * protocol instead of a process-local lock, which is out of scope until that ever changes. The
 * per-run {@code claimForCleanup}/{@code claimForArtifactPurge} guard still matters independently:
 * it is what lets a single sweep safely resume a run left tombstoned by an earlier, crashed process
 * (a different failure mode the in-process lock cannot help with, since that earlier process no
 * longer exists to hold any lock at all).
 *
 * <p><strong>Precedence</strong>: a run eligible for both protocols in the same sweep is only ever
 * fully cleaned up, never also artifact-purged in that same pass - see {@link #sweep}.
 *
 * <p><strong>Path safety</strong>: every filesystem path this class builds comes only from the
 * configured root directories ({@code runner.artifacts-dir}/{@code logs-dir}/{@code
 * raw-events-dir}) plus a {@code runId} re-validated against the exact UUID shape {@code
 * RunService#submit} always generates ({@link #requireValidRunId}) - never from any value read out
 * of the {@code artifacts} table's own {@code relative_path} column. Recursive deletion ({@link
 * #deleteRecursively}) never follows symlinks (the default for {@link Files#walkFileTree}/{@code
 * visitFile} - a symlink is reported to the visitor itself, never traversed into), so a symlink
 * planted inside a run's own directory is deleted itself, never used to escape it.
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
  private final Path artifactsDir;
  private final Path logsDir;
  private final Path rawEventsDir;

  /**
   * The in-process concurrency guard described in this class's own Javadoc - held only for a real
   * (non-dry-run) sweep's whole duration, via a non-blocking {@code tryLock} in {@link #sweep}.
   */
  private final ReentrantLock sweepLock = new ReentrantLock();

  public RetentionService(
      RunLifecycleStore runStore,
      ArtifactRepository artifactRepository,
      RunnerProperties properties) {
    this.runStore = runStore;
    this.artifactRepository = artifactRepository;
    this.properties = properties;
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
    // running - see this class's own Javadoc for why claimForCleanup/claimForArtifactPurge alone
    // do not close this gap.
    if (!sweepLock.tryLock()) {
      log.info(
          "Retention sweep skipped - another real sweep is already running in this process; the"
              + " next scheduled tick or manual trigger will pick up whatever is still eligible");
      return new RetentionReport(false, 0, 0, 0, 0, 0, 0, 0, true);
    }
    try {
      return runRealSweep();
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
        long freed = cleanupRun(runId, candidates.pendingCleanup.contains(runId));
        if (freed != CLAIM_LOST) {
          runDeleted++;
          bytesFreed += freed;
        }
      } catch (RuntimeException e) {
        log.error("Retention: full cleanup failed for run {} - will retry next sweep", runId, e);
        runFailed++;
      }
    }

    int purgeCompleted = 0;
    int purgeFailed = 0;
    for (String runId : candidates.artifactPurgeIds) {
      try {
        long freed = purgeArtifacts(runId, candidates.pendingPurge.contains(runId));
        if (freed != CLAIM_LOST) {
          purgeCompleted++;
          bytesFreed += freed;
        }
      } catch (RuntimeException e) {
        log.error("Retention: artifact purge failed for run {} - will retry next sweep", runId, e);
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
   * Kept separate from the newly-eligible set, not merged before this point: an already-tombstoned/
   * claimed run must never go through claimForCleanup/claimForArtifactPurge again - that call's own
   * contract is "did *this* call just win the claim", which a resumed run (claimed by an earlier,
   * possibly crashed, attempt) would always lose, silently skipping the resume entirely. See
   * cleanupRun/purgeArtifacts' own alreadyClaimed parameter.
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
   * Validate, claim (unless {@code alreadyClaimed} - see below), delete every on-disk file
   * (artifact directory, process log, raw event files), then delete the row - in that exact order.
   * Validating the {@code runId} shape <em>before</em> claiming means a malformed id (which should
   * never occur in practice - see this class's own Javadoc - but is checked anyway) is never
   * tombstoned at all, so it stays visible and simply fails (logged, retried, never crashing the
   * sweep) on every attempt rather than being hidden behind an unfinishable tombstone. A crash
   * anywhere after the claim always leaves a tombstoned-but-not-yet-fully-gone run for the next
   * sweep to safely resume (files may already be gone; deleting an already-gone file is success,
   * not an error), never a row deleted before its files are confirmed gone.
   *
   * @param alreadyClaimed {@code true} for a run resumed from {@link RunLifecycleStore#
   *     findPendingCleanup} - already tombstoned by an earlier (possibly crashed) attempt, so
   *     {@link RunLifecycleStore#claimForCleanup} must not be called again: that call's own
   *     contract is "did *this* call just win the claim," which a resumed run would always lose (it
   *     is already claimed), silently skipping the resume entirely. File/row deletion is idempotent
   *     regardless, so re-running it for an already-claimed run is always safe.
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
    // D4.2 - a run whose raw event stream overflowed its configured size cap gets this marker
    // instead of .tests.complete (see RunnerEventJsonlWriter's own Javadoc); it must be cleaned up
    // here too, or it would linger forever after the run itself is otherwise fully purged.
    deleteIfExists(rawEventsDir.resolve(safeRunId + ".tests.overflow"));
    runStore.deleteRun(runId);
    return freed;
  }

  /**
   * Validate, claim (unless {@code alreadyClaimed}), delete the artifact directory, then complete
   * the purge (delete rows + set purged_at) - see {@link #cleanupRun}'s own Javadoc for why
   * validation happens before the claim, and what {@code alreadyClaimed} means.
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
