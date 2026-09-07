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
   * <p>D4.1 - an entry whose target run currently has {@code artifacts_purge_started_at} set (purge
   * in progress or already complete) is silently skipped instead - never a conflict, and never a
   * chance for {@code ArtifactIngestionService}'s own background retry to resurrect metadata for
   * files a concurrent purge is deleting or has already deleted. D4.1 review round: this is now
   * enforced by a real per-run row lock held for the whole check-then-insert sequence, not only a
   * same-statement {@code WHERE EXISTS} guard - see {@code JdbcArtifactRepository}'s own Javadoc
   * for why the latter alone does not serialize against a genuinely concurrent purge transaction.
   *
   * @throws dev.vlaisanem.automation.runner.service.exception.ArtifactIngestionConflictException if
   *     any entry's {@code artifactId} already exists with different metadata.
   */
  void ingest(List<ArtifactManifestEntry> entries);

  /**
   * Every ingested entry for {@code runId}, optionally narrowed to one test's own artifacts when
   * {@code testIdFilter} is non-blank - ordered by {@code createdAt} then {@code artifactId} for a
   * stable, deterministic result across repeated calls.
   *
   * <p>D4.1 review round: empty for a run whose artifact purge has <em>started</em> - not only one
   * already fully complete - the instant {@code claimForArtifactPurge} commits, matching {@link
   * #isArtifactsPurged}'s own updated semantics, so a client is never handed a link to a file that
   * is (or is about to be) deleted.
   */
  List<ArtifactManifestEntry> findForRun(String runId, String testIdFilter);

  /**
   * Durably records that {@code runId}'s final artifact-ingestion drain (run immediately before
   * {@code RUN_FINISHED}) failed - the run itself still reaches its terminal status, but its
   * artifact metadata may be incomplete until a later reconciliation attempt succeeds. See {@code
   * ArtifactIngestionService}'s own bounded background reconciliation, the only other caller of
   * {@link #markIngestionComplete}.
   *
   * <p>D4.1 review round: a no-op for a run whose artifact purge has started or whose full cleanup
   * has already begun - flagging it would only make {@link #findRunIdsWithIncompleteIngestion}
   * retry a run that can never actually recover (its files are gone, or going).
   */
  void markIngestionIncomplete(String runId);

  /**
   * Clears the flag {@link #markIngestionIncomplete} sets, once a later attempt actually succeeds.
   */
  void markIngestionComplete(String runId);

  /**
   * Every {@code runId} currently flagged by {@link #markIngestionIncomplete}, oldest requested
   * first. D4.1 review round: never includes a run whose artifact purge or full cleanup has already
   * started - see {@link #markIngestionIncomplete}'s own Javadoc for why.
   */
  List<String> findRunIdsWithIncompleteIngestion();

  /**
   * Whether {@code runId} is currently flagged by {@link #markIngestionIncomplete} - lets {@code
   * ArtifactService}/{@code ArtifactController} distinguish "this run genuinely has zero artifacts"
   * from "ingestion for this run has not finished yet" instead of conflating the two.
   */
  boolean isIngestionIncomplete(String runId);

  /**
   * D4.1 - whether {@code runId}'s own artifacts are no longer available because of retention -
   * lets the dashboard distinguish "no artifacts were ever ingested" from "artifacts existed and
   * were purged" instead of a section silently vanishing. {@code false} for a runId that does not
   * exist at all (already fully cleaned up, or never existed) - never an error, since a caller here
   * is only ever asking "should I explain an empty artifact list," and both cases already show an
   * empty list for an unrelated reason.
   *
   * <p>D4.1 review round: {@code true} the instant purge <em>starts</em> ({@code
   * artifacts_purge_started_at IS NOT NULL}), not only once {@link #completePurge} has actually run
   * - the window between the two could otherwise leave a client looking at stale metadata for a
   * file already being deleted, or already gone.
   */
  boolean isArtifactsPurged(String runId);

  /**
   * D4.1 - the one atomic step of the artifact-purge protocol: deletes every {@code artifacts} row
   * for {@code runId} and sets {@code runs.artifacts_purged_at}, in one DB transaction, so a reader
   * can never observe one without the other. Must only ever be called after the run's own on-disk
   * artifact directory is confirmed gone (see {@code RetentionService}) - this method only ever
   * touches metadata rows, never the filesystem. D4.1 review round: also takes the same per-run row
   * lock {@link #ingest} does, so the two can never interleave incorrectly under real concurrency -
   * see {@code JdbcArtifactRepository}'s own Javadoc.
   */
  void completePurge(String runId);
}
