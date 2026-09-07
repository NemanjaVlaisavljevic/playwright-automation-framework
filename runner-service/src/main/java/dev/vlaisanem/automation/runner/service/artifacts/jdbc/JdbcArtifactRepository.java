package dev.vlaisanem.automation.runner.service.artifacts.jdbc;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.contract.ArtifactType;
import dev.vlaisanem.automation.runner.service.artifacts.ArtifactRepository;
import dev.vlaisanem.automation.runner.service.exception.ArtifactIngestionConflictException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D2.4 - the real {@link ArtifactRepository}, backed by the {@code artifacts} table Flyway created
 * back in D2.1. No row lock, no transaction of its own kind {@code JdbcRunStore} needs: unlike
 * {@code runs}/{@code run_events}, nothing here participates in the replay-atomicity protocol - an
 * artifact row has no lifecycle of its own to protect, only a one-shot idempotent insert.
 *
 * <p><strong>{@code ON CONFLICT (artifact_id) DO NOTHING} is idempotent only for a genuinely
 * identical re-read</strong> (a review finding): the same manifest entry may legitimately be
 * re-ingested by both an incremental pass and the final drain, and {@code DO NOTHING} is exactly
 * right for that case - but the same {@code artifactId} arriving with <em>different</em> metadata
 * (a different {@code runId}, path, type, size, or anything else) is not a legitimate re-read at
 * all, and {@code DO NOTHING} would otherwise silently discard that second artifact's real metadata
 * with no signal whatsoever. {@link #ingest} therefore inspects each row's own affected-count from
 * the batch (0 means a conflict occurred) and, only for those, re-reads the existing row and
 * compares it field-for-field against the incoming one - identical wins silently (the legitimate
 * case), anything else throws {@link ArtifactIngestionConflictException}.
 *
 * <p>{@code created_at} is truncated to microseconds before both the write and the comparison above
 * - {@code TIMESTAMPTZ} only stores microsecond precision (the same reason {@code JdbcRunStore}
 * truncates {@code Run}'s own timestamps), so an untruncated nanosecond-precision manifest {@link
 * Instant} would otherwise make a genuinely-identical re-ingest look like a conflict purely from a
 * precision mismatch that was never a real difference in the first place.
 *
 * <p><strong>D4.1 review round - {@code WHERE EXISTS(...)} alone does not serialize against a
 * concurrent purge under real transaction concurrency</strong> (a review finding): it correctly
 * removes the check-then-insert window <em>within one statement</em>, but does nothing to prevent
 * an ingest transaction's own snapshot from being taken before a concurrent {@code
 * claimForArtifactPurge} commits, then committing its insert only after that same run's purge has
 * already deleted its files and its {@code artifacts} rows - silently resurrecting metadata behind
 * files that no longer exist. {@link #ingest} now additionally takes a real per-run row lock (@code
 * SELECT ... FOR UPDATE} on {@code runs}) at the very start of its own transaction, for every
 * distinct target run in the batch, <em>before</em> checking the purge flag or inserting anything -
 * {@link #completePurge} takes the same lock at the start of its own transaction (the lock itself
 * is a plain {@code SELECT ... FOR UPDATE} against {@code runs}). Under Postgres's ordinary
 * row-lock semantics this makes the two protocols mutually exclusive per run, regardless of which
 * one reaches the row first: whichever transaction acquires the lock commits (or rolls back) before
 * the other can even read the row's current state, so there is no window left for a stale read to
 * slip through. {@code claimForArtifactPurge} (in {@code JdbcRunStore}) needs no code change of its
 * own to participate correctly - its {@code UPDATE} already takes the same row lock as an intrinsic
 * part of executing, for the duration of its own (very short, single-statement) transaction.
 */
@Component
public class JdbcArtifactRepository implements ArtifactRepository {

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;

  public JdbcArtifactRepository(
      JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
  }

  /**
   * D4.1 review round - see this class's own Javadoc for why a real per-run row lock, not just the
   * {@code WHERE EXISTS(...)} guard, is what actually makes this safe under real concurrent
   * transactions. Every distinct target run's row is locked (and its purge state read from that
   * locked read) before anything is inserted, all within one transaction spanning the whole batch.
   */
  @Override
  public void ingest(List<ArtifactManifestEntry> rawEntries) {
    if (rawEntries.isEmpty()) {
      return;
    }
    List<ArtifactManifestEntry> entries =
        rawEntries.stream().map(JdbcArtifactRepository::normalizeCreatedAt).toList();
    transactionTemplate.executeWithoutResult(status -> insertWithinPerRunLock(entries));
  }

  private void insertWithinPerRunLock(List<ArtifactManifestEntry> entries) {
    // Sorted so two concurrent multi-run batches (were ingest ever called with entries spanning
    // more than one run - it is not, today, see ArtifactIngestionService's own Javadoc, but this
    // makes that safe rather than merely assumed) always lock in the same order, ruling out a
    // lock-ordering deadlock between them.
    Set<String> runIds = new TreeSet<>();
    for (ArtifactManifestEntry entry : entries) {
      runIds.add(entry.runId());
    }
    Map<String, Boolean> purgeStartedByRunId = new HashMap<>();
    for (String runId : runIds) {
      purgeStartedByRunId.put(runId, lockRunAndCheckPurgeStarted(runId));
    }

    List<ArtifactManifestEntry> toInsert =
        entries.stream().filter(entry -> !purgeStartedByRunId.get(entry.runId())).toList();
    if (toInsert.isEmpty()) {
      return;
    }

    int[][] affectedPerBatch =
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO artifacts (artifact_id, run_id, test_id, test_display_name, step_id,
                schema_version, artifact_type, relative_path, size_bytes, media_type, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (artifact_id) DO NOTHING
            """,
            toInsert,
            toInsert.size(),
            (ps, entry) -> {
              ps.setString(1, entry.artifactId());
              ps.setString(2, entry.runId());
              ps.setString(3, entry.testId());
              ps.setString(4, entry.testDisplayName());
              ps.setString(5, entry.stepId());
              ps.setString(6, entry.schemaVersion());
              ps.setString(7, entry.type().name());
              ps.setString(8, entry.relativePath());
              ps.setLong(9, entry.sizeBytes());
              ps.setString(10, entry.mediaType());
              ps.setTimestamp(11, Timestamp.from(entry.createdAt()));
            });
    // batchSize == toInsert.size() above, so batchUpdate always produces exactly one chunk here -
    // affectedPerBatch[0][i] is toInsert.get(i)'s own affected-row count. Every entry reaching this
    // insert already passed the purge-lock check above, so 0 affected here can only mean a genuine
    // artifact_id conflict, never a purge-skip - no further disambiguation needed.
    int[] affected = affectedPerBatch[0];
    for (int i = 0; i < toInsert.size(); i++) {
      if (affected[i] == 0) {
        requireIdenticalToExistingRow(toInsert.get(i));
      }
    }
  }

  /**
   * Locks {@code runId}'s own {@code runs} row for the remainder of the current transaction (a
   * plain {@code SELECT ... FOR UPDATE}) and returns whether its artifact purge has started (or the
   * row does not exist at all - full-run cleanup deleted it out from under a very late ingestion
   * retry, an equally benign reason to skip). See this class's own Javadoc for why this lock, held
   * across the whole check-then-insert sequence, is what actually closes the race a bare {@code
   * WHERE EXISTS} read could not.
   */
  private boolean lockRunAndCheckPurgeStarted(String runId) {
    List<Boolean> rows =
        jdbcTemplate.query(
            "SELECT artifacts_purge_started_at IS NOT NULL AS purge_started FROM runs"
                + " WHERE run_id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getBoolean("purge_started"),
            runId);
    return rows.isEmpty() || rows.get(0);
  }

  private void requireIdenticalToExistingRow(ArtifactManifestEntry incoming) {
    ArtifactManifestEntry existing = findById(incoming.artifactId());
    if (existing == null || !existing.equals(incoming)) {
      throw new ArtifactIngestionConflictException(incoming.artifactId(), existing, incoming);
    }
  }

  private ArtifactManifestEntry findById(String artifactId) {
    List<ArtifactManifestEntry> matches =
        jdbcTemplate.query(
            "SELECT * FROM artifacts WHERE artifact_id = ?",
            (rs, rowNum) -> toEntry(rs),
            artifactId);
    return matches.stream().findFirst().orElse(null);
  }

  /**
   * D4.1 review round (P2 finding): excludes any run whose artifact purge has <em>started</em> -
   * not only one already fully complete - so a client can never be handed a download link for a
   * file that is (or is about to be) deleted. Before this, the list stayed populated with stale
   * rows for the entire window between {@code claimForArtifactPurge} and {@link #completePurge}
   * (the on-disk file may already be gone by then, since deletion happens before that call).
   */
  @Override
  public List<ArtifactManifestEntry> findForRun(String runId, String testIdFilter) {
    if (testIdFilter == null || testIdFilter.isBlank()) {
      return jdbcTemplate.query(
          """
          SELECT * FROM artifacts WHERE run_id = ?
            AND EXISTS (SELECT 1 FROM runs WHERE run_id = ? AND artifacts_purge_started_at IS NULL)
          ORDER BY created_at, artifact_id
          """,
          (rs, rowNum) -> toEntry(rs),
          runId,
          runId);
    }
    return jdbcTemplate.query(
        """
        SELECT * FROM artifacts WHERE run_id = ? AND test_id = ?
          AND EXISTS (SELECT 1 FROM runs WHERE run_id = ? AND artifacts_purge_started_at IS NULL)
        ORDER BY created_at, artifact_id
        """,
        (rs, rowNum) -> toEntry(rs),
        runId,
        testIdFilter,
        runId);
  }

  /**
   * D4.1 review round: excludes a run whose artifact purge has already started (or full-run cleanup
   * has already tombstoned it) - retrying ingestion for a run that is being (or has been) purged
   * can never usefully succeed, since {@link #ingest} will just keep skipping it anyway, and
   * flagging it incomplete would only add noise to {@link #findRunIdsWithIncompleteIngestion} and
   * waste bounded reconciliation attempts on a run that will never actually recover.
   */
  @Override
  public void markIngestionIncomplete(String runId) {
    jdbcTemplate.update(
        "UPDATE runs SET artifacts_ingestion_incomplete = true WHERE run_id = ?"
            + " AND artifacts_purge_started_at IS NULL AND cleanup_started_at IS NULL",
        runId);
  }

  @Override
  public void markIngestionComplete(String runId) {
    jdbcTemplate.update(
        "UPDATE runs SET artifacts_ingestion_incomplete = false WHERE run_id = ?"
            + " AND artifacts_purge_started_at IS NULL AND cleanup_started_at IS NULL",
        runId);
  }

  /** D4.1 review round: same exclusion as {@link #markIngestionIncomplete}'s own Javadoc. */
  @Override
  public List<String> findRunIdsWithIncompleteIngestion() {
    return jdbcTemplate.queryForList(
        "SELECT run_id FROM runs WHERE artifacts_ingestion_incomplete = true"
            + " AND artifacts_purge_started_at IS NULL AND cleanup_started_at IS NULL"
            + " ORDER BY requested_at",
        String.class);
  }

  @Override
  public boolean isIngestionIncomplete(String runId) {
    Boolean incomplete =
        jdbcTemplate.queryForObject(
            "SELECT artifacts_ingestion_incomplete FROM runs WHERE run_id = ?",
            Boolean.class,
            runId);
    return Boolean.TRUE.equals(incomplete);
  }

  /**
   * D4.1 review round (P2 finding): reports {@code true} the instant purge <em>starts</em> ({@code
   * artifacts_purge_started_at IS NOT NULL}), not only once it fully completes ({@code
   * artifacts_purged_at IS NOT NULL}) - matches {@link #findForRun}'s own updated exclusion, so the
   * dashboard's "artifacts expired due to retention" message appears the moment a client can no
   * longer see any artifacts for this run, not only once the DB row cleanup finishes.
   */
  @Override
  public boolean isArtifactsPurged(String runId) {
    List<Boolean> rows =
        jdbcTemplate.query(
            "SELECT artifacts_purge_started_at IS NOT NULL AS purge_started FROM runs"
                + " WHERE run_id = ?",
            (rs, rowNum) -> rs.getBoolean("purge_started"),
            runId);
    return !rows.isEmpty() && rows.get(0);
  }

  /**
   * D4.1 review round - see this class's own Javadoc for why locking {@code runId}'s row first,
   * within this same transaction, is what actually serializes this against a concurrent {@link
   * #ingest} rather than merely the pre-existing {@code DELETE}+{@code UPDATE} pair alone.
   */
  @Override
  public void completePurge(String runId) {
    transactionTemplate.executeWithoutResult(
        status -> {
          lockRunAndCheckPurgeStarted(runId);
          jdbcTemplate.update("DELETE FROM artifacts WHERE run_id = ?", runId);
          jdbcTemplate.update(
              "UPDATE runs SET artifacts_purged_at = now() WHERE run_id = ?", runId);
        });
  }

  /**
   * {@code TIMESTAMPTZ} only stores microsecond precision - see this class's own Javadoc for why
   * the comparison {@link #requireIdenticalToExistingRow} makes must never be tripped up by a
   * precision difference that was never a real one.
   */
  private static ArtifactManifestEntry normalizeCreatedAt(ArtifactManifestEntry entry) {
    Instant truncated = entry.createdAt().truncatedTo(ChronoUnit.MICROS);
    if (truncated.equals(entry.createdAt())) {
      return entry;
    }
    return new ArtifactManifestEntry(
        entry.schemaVersion(),
        entry.artifactId(),
        entry.runId(),
        entry.testId(),
        entry.testDisplayName(),
        entry.stepId(),
        entry.type(),
        entry.relativePath(),
        entry.mediaType(),
        entry.sizeBytes(),
        truncated);
  }

  private static ArtifactManifestEntry toEntry(ResultSet rs) throws SQLException {
    return new ArtifactManifestEntry(
        rs.getString("schema_version"),
        rs.getString("artifact_id"),
        rs.getString("run_id"),
        rs.getString("test_id"),
        rs.getString("test_display_name"),
        rs.getString("step_id"),
        ArtifactType.valueOf(rs.getString("artifact_type")),
        rs.getString("relative_path"),
        rs.getString("media_type"),
        rs.getLong("size_bytes"),
        rs.getTimestamp("created_at").toInstant());
  }
}
