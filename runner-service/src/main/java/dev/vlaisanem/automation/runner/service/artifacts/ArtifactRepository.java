package dev.vlaisanem.automation.runner.service.artifacts;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import java.util.List;

/**
 * The durable index over a run's artifact manifest. {@code artifacts} is a derived store: {@link
 * ArtifactIngestionService} is the only writer, inserting fresh manifest entries idempotently by
 * {@code artifactId}; {@link ArtifactService} is the only reader.
 */
public interface ArtifactRepository {

  /**
   * Inserts every entry not already present (by {@code artifactId}) - safe to call repeatedly with
   * an overlapping set, since an incremental pass and the final drain may see the same lines. An
   * entry whose {@code artifactId} already exists with different metadata is a genuine
   * data-integrity problem, never silently accepted.
   *
   * <p>An entry whose target run has {@code artifacts_purge_started_at} set is silently skipped
   * instead, so a background retry can never resurrect metadata for files a concurrent purge is
   * deleting or has already deleted.
   *
   * @throws dev.vlaisanem.automation.runner.service.exception.ArtifactIngestionConflictException if
   *     any entry's {@code artifactId} already exists with different metadata.
   */
  void ingest(List<ArtifactManifestEntry> entries);

  /**
   * Every ingested entry for {@code runId}, optionally narrowed to one test's artifacts, ordered by
   * {@code createdAt} then {@code artifactId} for a stable, deterministic result.
   *
   * <p>Empty for a run whose artifact purge has started (not only one already complete), matching
   * {@link #isArtifactsPurged}, so a client is never handed a link to a file being deleted.
   */
  List<ArtifactManifestEntry> findForRun(String runId, String testIdFilter);

  /**
   * Durably records that {@code runId}'s final artifact-ingestion drain failed - the run still
   * reaches its terminal status, but its artifact metadata may be incomplete until a later
   * reconciliation attempt succeeds.
   *
   * <p>A no-op for a run whose artifact purge or cleanup has already begun, since flagging it would
   * only make {@link #findRunIdsWithIncompleteIngestion} retry a run that can never recover.
   */
  void markIngestionIncomplete(String runId);

  /**
   * Clears the flag {@link #markIngestionIncomplete} sets, once a later attempt actually succeeds.
   */
  void markIngestionComplete(String runId);

  /**
   * Every {@code runId} currently flagged by {@link #markIngestionIncomplete}, oldest requested
   * first; never includes a run whose artifact purge or cleanup has already started.
   */
  List<String> findRunIdsWithIncompleteIngestion();

  /**
   * Whether {@code runId} is currently flagged by {@link #markIngestionIncomplete} - lets {@code
   * ArtifactService}/{@code ArtifactController} distinguish "this run genuinely has zero artifacts"
   * from "ingestion for this run has not finished yet" instead of conflating the two.
   */
  boolean isIngestionIncomplete(String runId);

  /**
   * Whether {@code runId}'s artifacts are no longer available because of retention, so the
   * dashboard can distinguish "never ingested" from "existed and were purged". {@code false} for a
   * runId that doesn't exist at all - never an error. {@code true} the instant purge starts, not
   * only once {@link #completePurge} has run, so a client is never shown stale metadata for a file
   * already being deleted.
   */
  boolean isArtifactsPurged(String runId);

  /**
   * The one atomic step of the artifact-purge protocol: deletes every {@code artifacts} row for
   * {@code runId} and sets {@code runs.artifacts_purged_at} in one transaction, so a reader can
   * never observe one without the other. Must only be called after the run's on-disk artifact
   * directory is confirmed gone (see {@code RetentionService}) - this method only touches metadata
   * rows, never the filesystem.
   */
  void completePurge(String runId);
}
