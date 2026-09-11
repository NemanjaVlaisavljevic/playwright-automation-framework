package dev.vlaisanem.automation.runner.service.artifacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.exception.ArtifactManifestCorruptException;
import dev.vlaisanem.automation.runner.service.filesystem.RunFilePaths;
import dev.vlaisanem.automation.runner.service.logging.MdcScope;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads a run's {@code manifest.jsonl} and ingests any new entries into the {@code artifacts} table
 * via {@link ArtifactRepository#ingest}. Called incrementally, not just once at the end, so a
 * failing test's screenshot/trace is visible before the whole run finishes; always re-reads the
 * whole manifest and relies on {@code ingest}'s own idempotency rather than tracking an offset.
 *
 * <p>Never throws: a read/parse/database failure here must not block the lifecycle event that
 * triggered it. A failed final ({@code runTerminal}) drain is durably flagged via {@link
 * ArtifactRepository#markIngestionIncomplete} and retried by a bounded background reconciliation
 * loop (up to {@link #MAX_RECONCILIATION_ATTEMPTS} times); a run that never recovers stays flagged
 * as a visible data-integrity signal rather than retried forever.
 */
@Component
public class ArtifactIngestionService {

  private static final Logger log = LoggerFactory.getLogger(ArtifactIngestionService.class);
  private static final Duration RECONCILIATION_INTERVAL = Duration.ofSeconds(30);
  private static final int MAX_RECONCILIATION_ATTEMPTS = 5;

  private final ArtifactManifestReader manifestReader;
  private final ArtifactRepository repository;
  private final Path artifactsRootDir;
  private final long manifestMaxBytes;
  private final long artifactMaxBytes;
  private final ScheduledExecutorService reconciliationExecutor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "artifact-ingestion-reconciliation");
            thread.setDaemon(true);
            return thread;
          });
  private final Map<String, Integer> reconciliationAttempts = new ConcurrentHashMap<>();

  public ArtifactIngestionService(
      ObjectMapper objectMapper, ArtifactRepository repository, RunnerProperties properties) {
    this.manifestReader = new ArtifactManifestReader(objectMapper);
    this.repository = repository;
    this.artifactsRootDir = Path.of(properties.artifactsDir()).toAbsolutePath().normalize();
    this.manifestMaxBytes = properties.manifestMaxBytes();
    this.artifactMaxBytes = properties.artifactMaxBytes();
  }

  /**
   * A {@code @PostConstruct} hook, not constructor logic, so a test that constructs this class
   * directly with {@code new} never gets a live background thread it can't shut down.
   */
  @PostConstruct
  public void startReconciliation() {
    reconciliationExecutor.scheduleWithFixedDelay(
        this::reconcileIncompleteRunsOnce,
        RECONCILIATION_INTERVAL.toMillis(),
        RECONCILIATION_INTERVAL.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  @PreDestroy
  public void shutdown() {
    reconciliationExecutor.shutdownNow();
  }

  /**
   * @param runTerminal {@code false} for an incremental pass (the writer may still be mid-append),
   *     {@code true} only for the final drain and its reconciliation retries. Only a {@code true}
   *     call marks or clears the ingestion-incomplete flag; an incremental pass's failure always
   *     has a following attempt that can still succeed on its own.
   */
  public ArtifactIngestionOutcome ingestAvailableEntries(String runId, boolean runTerminal) {
    try {
      Path runRoot = RunFilePaths.artifactsDirectory(artifactsRootDir, runId);
      if (Files.isSymbolicLink(runRoot)) {
        throw new ArtifactManifestCorruptException(
            runId, "run artifacts directory must not be a symbolic link: " + runRoot);
      }
      Path manifestFile = RunFilePaths.artifactManifest(artifactsRootDir, runId);
      List<ArtifactManifestEntry> entries =
          manifestReader.read(manifestFile, runId, runTerminal, manifestMaxBytes);
      // The producer already deletes an oversized artifact before recording it in the manifest,
      // so a real/manifested size mismatch here can only mean a bug, a race, or tampering; treated
      // as manifest corruption, and the file is never auto-deleted since it's worth investigating.
      for (ArtifactManifestEntry entry : entries) {
        Path resolved = ArtifactFileResolver.resolve(artifactsRootDir, runId, entry);
        long realSize;
        try {
          realSize = Files.size(resolved);
        } catch (IOException e) {
          throw new ArtifactManifestCorruptException(
              runId, "could not stat " + resolved + ": " + e.getMessage());
        }
        if (realSize != entry.sizeBytes()) {
          throw new ArtifactManifestCorruptException(
              runId,
              "artifact "
                  + entry.artifactId()
                  + " at "
                  + resolved
                  + " is "
                  + realSize
                  + " bytes on disk but the manifest claims "
                  + entry.sizeBytes());
        }
        // Independently enforces the per-artifact limit rather than trusting the producer: an
        // oversized file must not be served just because its manifest entry agrees with its size.
        if (realSize > artifactMaxBytes) {
          throw new ArtifactManifestCorruptException(
              runId,
              "artifact "
                  + entry.artifactId()
                  + " at "
                  + resolved
                  + " is "
                  + realSize
                  + " bytes, exceeding the configured "
                  + artifactMaxBytes
                  + "-byte per-artifact limit");
        }
      }
      repository.ingest(entries);
      if (runTerminal) {
        repository.markIngestionComplete(runId);
        reconciliationAttempts.remove(runId);
      }
      return ArtifactIngestionOutcome.SUCCEEDED;
    } catch (RuntimeException e) {
      // runId as a structured/ECS field, not only interpolated into the message, so a
      // reconciliation-pass failure is just as searchable as an incremental-pass failure.
      log.atWarn()
          .addKeyValue("runId", runId)
          .setCause(e)
          .log(
              "Artifact ingestion failed (runTerminal="
                  + runTerminal
                  + ") - artifacts remain a derived index, so this does not fail the run itself,"
                  + " but its artifact list may be incomplete until a later successful pass: "
                  + e.getMessage());
      if (runTerminal) {
        repository.markIngestionIncomplete(runId);
      }
      return ArtifactIngestionOutcome.FAILED;
    }
  }

  /**
   * One reconciliation pass. Package-private so a test can drive it deterministically instead of
   * waiting on the real {@link #RECONCILIATION_INTERVAL} timer.
   */
  void reconcileIncompleteRunsOnce() {
    try {
      for (String runId : repository.findRunIdsWithIncompleteIngestion()) {
        int attemptsSoFar = reconciliationAttempts.getOrDefault(runId, 0);
        if (attemptsSoFar >= MAX_RECONCILIATION_ATTEMPTS) {
          // A manifest that never recovers must not be retried forever - it stays flagged rather
          // than burning a background thread on it permanently.
          continue;
        }
        reconciliationAttempts.put(runId, attemptsSoFar + 1);
        // Runs on its own executor thread, so MDC doesn't carry runId onto it automatically.
        MdcScope.withMdc("runId", runId, () -> ingestAvailableEntries(runId, true));
      }
    } catch (RuntimeException e) {
      // Must not propagate: scheduleWithFixedDelay stops retrying entirely if the task ever
      // throws once.
      log.warn("Artifact ingestion reconciliation pass failed: {}", e.getMessage(), e);
    }
  }

  /** Test-only visibility into the bounded retry counter; not part of the public contract. */
  int reconciliationAttemptsFor(String runId) {
    return reconciliationAttempts.getOrDefault(runId, 0);
  }
}
