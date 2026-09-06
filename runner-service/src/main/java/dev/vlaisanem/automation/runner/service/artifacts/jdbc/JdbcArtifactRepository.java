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
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
 */
@Component
public class JdbcArtifactRepository implements ArtifactRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcArtifactRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void ingest(List<ArtifactManifestEntry> rawEntries) {
    if (rawEntries.isEmpty()) {
      return;
    }
    List<ArtifactManifestEntry> entries =
        rawEntries.stream().map(JdbcArtifactRepository::normalizeCreatedAt).toList();
    int[][] affectedPerBatch =
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO artifacts (artifact_id, run_id, test_id, test_display_name, step_id,
                schema_version, artifact_type, relative_path, size_bytes, media_type, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (artifact_id) DO NOTHING
            """,
            entries,
            entries.size(),
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
    // batchSize == entries.size() above, so batchUpdate always produces exactly one chunk here -
    // affectedPerBatch[0][i] is entries.get(i)'s own affected-row count (0 == conflicted).
    int[] affected = affectedPerBatch[0];
    for (int i = 0; i < entries.size(); i++) {
      if (affected[i] == 0) {
        requireIdenticalToExistingRow(entries.get(i));
      }
    }
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

  @Override
  public List<ArtifactManifestEntry> findForRun(String runId, String testIdFilter) {
    if (testIdFilter == null || testIdFilter.isBlank()) {
      return jdbcTemplate.query(
          "SELECT * FROM artifacts WHERE run_id = ? ORDER BY created_at, artifact_id",
          (rs, rowNum) -> toEntry(rs),
          runId);
    }
    return jdbcTemplate.query(
        "SELECT * FROM artifacts WHERE run_id = ? AND test_id = ? ORDER BY created_at, artifact_id",
        (rs, rowNum) -> toEntry(rs),
        runId,
        testIdFilter);
  }

  @Override
  public void markIngestionIncomplete(String runId) {
    jdbcTemplate.update(
        "UPDATE runs SET artifacts_ingestion_incomplete = true WHERE run_id = ?", runId);
  }

  @Override
  public void markIngestionComplete(String runId) {
    jdbcTemplate.update(
        "UPDATE runs SET artifacts_ingestion_incomplete = false WHERE run_id = ?", runId);
  }

  @Override
  public List<String> findRunIdsWithIncompleteIngestion() {
    return jdbcTemplate.queryForList(
        "SELECT run_id FROM runs WHERE artifacts_ingestion_incomplete = true ORDER BY requested_at",
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
