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
 * The real {@link ArtifactRepository}, backed by the {@code artifacts} table. An artifact row has
 * no lifecycle of its own to protect, only a one-shot idempotent insert.
 *
 * <p>{@code ON CONFLICT (artifact_id) DO NOTHING} is idempotent only for a genuinely identical
 * re-read (an incremental pass and the final drain may legitimately re-ingest the same entry). The
 * same {@code artifactId} arriving with different metadata is not a legitimate re-read: {@link
 * #ingest} inspects each row's affected-count (0 means a conflict), re-reads the existing row, and
 * throws {@link ArtifactIngestionConflictException} unless it's field-for-field identical.
 *
 * <p>{@code created_at} is truncated to microseconds before both the write and that comparison,
 * since {@code TIMESTAMPTZ} only stores microsecond precision and an untruncated {@link Instant}
 * would otherwise look like a spurious conflict.
 *
 * <p>{@code ingest} and {@link #completePurge} each take a real per-run row lock ({@code SELECT ...
 * FOR UPDATE} on {@code runs}) before touching artifact rows, making the two protocols mutually
 * exclusive per run under Postgres's row-lock semantics - a same-statement {@code WHERE EXISTS}
 * guard alone can't prevent an ingest transaction from committing after a concurrent purge has
 * already deleted that run's files, which would silently resurrect metadata for gone files.
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
   * Every distinct target run's row is locked (and its purge state read from that locked read)
   * before anything is inserted, all within one transaction spanning the whole batch.
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
    // Sorted so two concurrent multi-run batches always lock in the same order, ruling out a
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
    // batchSize == toInsert.size(), so batchUpdate produces exactly one chunk:
    // affectedPerBatch[0][i]
    // is toInsert.get(i)'s affected-row count. 0 here can only mean a genuine artifact_id conflict,
    // since every entry already passed the purge-lock check above.
    int[] affected = affectedPerBatch[0];
    for (int i = 0; i < toInsert.size(); i++) {
      if (affected[i] == 0) {
        requireIdenticalToExistingRow(toInsert.get(i));
      }
    }
  }

  /**
   * Locks {@code runId}'s {@code runs} row for the rest of the current transaction and returns
   * whether its artifact purge has started (or the row doesn't exist at all - full-run cleanup
   * deleted it out from under a very late ingestion retry, an equally benign reason to skip).
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
   * Excludes any run whose artifact purge has started, not only one already complete, so a client
   * can never be handed a download link for a file that is (or is about to be) deleted.
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
   * Excludes a run whose artifact purge or full-run cleanup has already started: retrying ingestion
   * for a run being (or already) purged can never usefully succeed, since {@link #ingest} will just
   * keep skipping it.
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

  /** Same exclusion as {@link #markIngestionIncomplete}. */
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
   * Reports {@code true} the instant purge starts, not only once it fully completes, matching
   * {@link #findForRun}'s exclusion so the "expired due to retention" message appears as soon as a
   * client can no longer see any artifacts for this run.
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
   * Locks {@code runId}'s row first, within this same transaction, to serialize against {@link
   * #ingest}.
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
   * {@code TIMESTAMPTZ} only stores microsecond precision, so {@link
   * #requireIdenticalToExistingRow}'s comparison must not be tripped up by a precision difference
   * that was never a real one.
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
