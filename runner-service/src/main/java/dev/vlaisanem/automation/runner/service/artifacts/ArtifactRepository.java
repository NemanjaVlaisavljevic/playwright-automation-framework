package dev.vlaisanem.automation.runner.service.artifacts;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import java.util.List;

/**
 * D2.4 - the durable index over a run's artifact manifest: {@code artifacts} is a derived store,
 * never the other way around - {@link ArtifactIngestionService} is the only writer, reading fresh
 * entries from the manifest {@code ArtifactManifestWriter} (the main automation suite) produces and
 * inserting them here, idempotently by {@code artifactId}. {@link ArtifactService} is the only
 * reader, entirely replacing the pre-D2.4 direct manifest-file read on every request.
 */
public interface ArtifactRepository {

  /**
   * Inserts every entry not already present (by {@code artifactId}) - safe to call repeatedly with
   * the same, or an overlapping, set of entries: an incremental pass and the final pre-{@code
   * RUN_FINISHED} drain may legitimately both see the same manifest lines. An incoming entry whose
   * {@code artifactId} already exists with <em>different</em> metadata (a different {@code runId},
   * path, type, size, or any other field) is a genuine data-integrity problem, never silently
   * accepted - see {@code JdbcArtifactRepository}'s own Javadoc for why idempotency only actually
   * applies to a byte-for-byte-identical re-read of the same entry.
   *
   * @throws dev.vlaisanem.automation.runner.service.exception.ArtifactIngestionConflictException if
   *     any entry's {@code artifactId} already exists with different metadata.
   */
  void ingest(List<ArtifactManifestEntry> entries);

  /**
   * Every ingested entry for {@code runId}, optionally narrowed to one test's own artifacts when
   * {@code testIdFilter} is non-blank - ordered by {@code createdAt} then {@code artifactId} for a
   * stable, deterministic result across repeated calls.
   */
  List<ArtifactManifestEntry> findForRun(String runId, String testIdFilter);

  /**
   * Durably records that {@code runId}'s final artifact-ingestion drain (run immediately before
   * {@code RUN_FINISHED}) failed - the run itself still reaches its terminal status, but its
   * artifact metadata may be incomplete until a later reconciliation attempt succeeds. See {@code
   * ArtifactIngestionService}'s own bounded background reconciliation, the only other caller of
   * {@link #markIngestionComplete}.
   */
  void markIngestionIncomplete(String runId);

  /**
   * Clears the flag {@link #markIngestionIncomplete} sets, once a later attempt actually succeeds.
   */
  void markIngestionComplete(String runId);

  /**
   * Every {@code runId} currently flagged by {@link #markIngestionIncomplete}, oldest requested
   * first.
   */
  List<String> findRunIdsWithIncompleteIngestion();

  /**
   * Whether {@code runId} is currently flagged by {@link #markIngestionIncomplete} - lets {@code
   * ArtifactService}/{@code ArtifactController} distinguish "this run genuinely has zero artifacts"
   * from "ingestion for this run has not finished yet" instead of conflating the two.
   */
  boolean isIngestionIncomplete(String runId);
}
