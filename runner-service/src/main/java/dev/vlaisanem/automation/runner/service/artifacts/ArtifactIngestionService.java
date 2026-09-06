package dev.vlaisanem.automation.runner.service.artifacts;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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
 * D2.4 - reads a run's {@code manifest.jsonl} (the one raw, original piece of evidence {@code
 * ArtifactManifestWriter} produces) and ingests any entries not yet in the {@code artifacts} table
 * into it, via {@link ArtifactRepository#ingest}. Called incrementally, not just once at the end -
 * see docs/DEPLOYMENT_ARCHITECTURE.md's "Artifacts must ingest incrementally" section for why a
 * bulk {@code RUN_FINISHED}-only import would regress the dashboard's already-proven early artifact
 * visibility (a failing test's screenshot/trace shown before the whole run finishes): {@link
 * dev.vlaisanem.automation.runner.service.events.RunEventBroker#append} calls {@link
 * #ingestAvailableEntries} <em>before</em> publishing a {@code TEST_FAILED}/{@code TEST_ABORTED}
 * event to any live subscriber (a review finding: a client that invalidates its artifacts query the
 * instant it observes that event over SSE must never be able to race ahead of this ingestion and
 * see an empty list with no further chance to refresh before {@code RUN_FINISHED}), and {@code
 * RunLifecycleCoordinator#finishIfLive} calls it once more as a final drain immediately before
 * {@code RUN_FINISHED} is recorded (covering a test that failed without a screenshot capture, or
 * any entry the last incremental pass hadn't yet seen).
 *
 * <p>Always re-reads the whole manifest and relies on {@link ArtifactRepository#ingest}'s own
 * idempotency, rather than tracking a byte/line offset itself - the same manifest entry may
 * legitimately be re-read by both an incremental pass and the final drain, and re-parsing a whole
 * run's manifest (bounded by how many artifacts one run can produce) is cheap enough that tracking
 * an offset would only add complexity for no real gain.
 *
 * <p><strong>Never throws</strong> - a read/parse/database failure here must never prevent the
 * lifecycle event that triggered it (the {@code TEST_FAILED}/{@code TEST_ABORTED} event still gets
 * appended and published; {@code RUN_FINISHED} still gets recorded) from proceeding; every failure
 * is logged, and {@link #ingestAvailableEntries}'s own {@link ArtifactIngestionOutcome} return
 * value tells the caller which happened.
 *
 * <p><strong>A failed final ({@code runTerminal}) drain is not silently forgotten</strong> (a
 * review finding): it durably flags the run via {@link ArtifactRepository#markIngestionIncomplete},
 * and a bounded background reconciliation pass (started by {@link #startReconciliation()}, a
 * {@code @PostConstruct} hook - deliberately not scheduled from the constructor itself, so a test
 * that constructs this class directly with {@code new}, bypassing Spring, never gets a live
 * background thread it has no way to shut down again) periodically retries every currently-flagged
 * run, up to {@link #MAX_RECONCILIATION_ATTEMPTS} times each, clearing the flag via {@link
 * ArtifactRepository#markIngestionComplete} the moment a retry actually succeeds. A run that still
 * fails after every bounded attempt stays flagged permanently - a genuine, durably visible
 * data-integrity signal ({@code ArtifactService}/{@code ArtifactController} expose it rather than
 * silently reporting "zero artifacts"), not an infinite retry loop against a manifest that will
 * never recover (a deleted artifacts directory, for instance).
 */
@Component
public class ArtifactIngestionService {

  private static final Logger log = LoggerFactory.getLogger(ArtifactIngestionService.class);
  private static final String MANIFEST_FILE_NAME = "manifest.jsonl";
  private static final Duration RECONCILIATION_INTERVAL = Duration.ofSeconds(30);
  private static final int MAX_RECONCILIATION_ATTEMPTS = 5;

  private final ArtifactManifestReader manifestReader;
  private final ArtifactRepository repository;
  private final Path artifactsRootDir;
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
  }

  /**
   * Starts the bounded background reconciliation loop - a {@code @PostConstruct} hook, not
   * constructor logic, so only a real Spring-managed instance ever gets a live background thread;
   * see this class's own Javadoc for why a test-constructed instance must not.
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
   * @param runTerminal mirrors {@link ArtifactManifestReader#read}'s own parameter: {@code false}
   *     for every incremental pass (the writer may still be mid-append), {@code true} only for the
   *     final drain (and its own later reconciliation retries), called after the run's own process
   *     has already exited and its manifest can no longer grow. Only a {@code true} call ever marks
   *     or clears {@link ArtifactRepository#markIngestionIncomplete}/{@link
   *     ArtifactRepository#markIngestionComplete} - an incremental pass's own failure always has a
   *     following attempt (another incremental pass, or the final drain) that can still succeed on
   *     its own, so it needs no separate durable tracking of its own.
   */
  public ArtifactIngestionOutcome ingestAvailableEntries(String runId, boolean runTerminal) {
    try {
      Path manifestFile = artifactsRootDir.resolve(runId).resolve(MANIFEST_FILE_NAME);
      List<ArtifactManifestEntry> entries = manifestReader.read(manifestFile, runId, runTerminal);
      repository.ingest(entries);
      if (runTerminal) {
        repository.markIngestionComplete(runId);
        reconciliationAttempts.remove(runId);
      }
      return ArtifactIngestionOutcome.SUCCEEDED;
    } catch (RuntimeException e) {
      log.warn(
          "Artifact ingestion failed for run {} (runTerminal={}) - artifacts remain a derived"
              + " index, so this does not fail the run itself, but its artifact list may be"
              + " incomplete until a later successful pass: {}",
          runId,
          runTerminal,
          e.getMessage(),
          e);
      if (runTerminal) {
        repository.markIngestionIncomplete(runId);
      }
      return ArtifactIngestionOutcome.FAILED;
    }
  }

  /**
   * One reconciliation pass: retries every run {@link
   * ArtifactRepository#findRunIdsWithIncompleteIngestion} currently reports, up to {@link
   * #MAX_RECONCILIATION_ATTEMPTS} times each. Package-private so a test can drive one pass
   * deterministically instead of waiting on the real {@link #RECONCILIATION_INTERVAL} timer.
   */
  void reconcileIncompleteRunsOnce() {
    try {
      for (String runId : repository.findRunIdsWithIncompleteIngestion()) {
        int attemptsSoFar = reconciliationAttempts.getOrDefault(runId, 0);
        if (attemptsSoFar >= MAX_RECONCILIATION_ATTEMPTS) {
          // Bounded, not infinite: a manifest that will never recover (e.g. its artifacts
          // directory was deleted) must not be retried forever - it stays flagged, a permanent
          // and genuinely visible signal, rather than burning a background thread on it forever.
          continue;
        }
        reconciliationAttempts.put(runId, attemptsSoFar + 1);
        ingestAvailableEntries(runId, true);
      }
    } catch (RuntimeException e) {
      // The reconciliation loop itself (e.g. findRunIdsWithIncompleteIngestion failing against a
      // momentarily unreachable database) must never kill the scheduled task permanently -
      // scheduleWithFixedDelay stops retrying entirely if the task ever throws once.
      log.warn("Artifact ingestion reconciliation pass failed: {}", e.getMessage(), e);
    }
  }

  /**
   * Test-only visibility into the bounded retry counter - package-private, not part of the public
   * contract, so a test can assert reconciliation actually stops at {@link
   * #MAX_RECONCILIATION_ATTEMPTS} rather than retrying forever, without needing to observe that
   * indirectly.
   */
  int reconciliationAttemptsFor(String runId) {
    return reconciliationAttempts.getOrDefault(runId, 0);
  }
}
