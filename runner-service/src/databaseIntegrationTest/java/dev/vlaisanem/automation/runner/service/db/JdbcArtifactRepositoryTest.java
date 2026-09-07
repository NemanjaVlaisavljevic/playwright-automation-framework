package dev.vlaisanem.automation.runner.service.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.contract.ArtifactType;
import dev.vlaisanem.automation.runner.service.artifacts.jdbc.JdbcArtifactRepository;
import dev.vlaisanem.automation.runner.service.exception.ArtifactIngestionConflictException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * D2.4 - proves {@link JdbcArtifactRepository} against a real Postgres: a round trip through the
 * {@code artifacts} table preserves every {@link ArtifactManifestEntry} field exactly, {@code
 * ingest}'s {@code ON CONFLICT (artifact_id) DO NOTHING} genuinely makes re-ingesting the same
 * entry a no-op (never overwriting whatever was already ingested for that {@code artifactId}), and
 * {@code findForRun} both filters by {@code testId} and orders deterministically. Every entry needs
 * a real parent {@code runs} row first - {@code fk_artifacts_run} enforces that, same as {@code
 * run_events}' own parent-row requirement in {@link JdbcRunStoreTest}.
 */
@Testcontainers
class JdbcArtifactRepositoryTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine");

  private static JdbcTemplate jdbcTemplate;
  private static JdbcArtifactRepository repository;

  @BeforeAll
  static void migrateAndBuildRepository() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();

    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());

    jdbcTemplate = new JdbcTemplate(dataSource);
    TransactionTemplate transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    repository = new JdbcArtifactRepository(jdbcTemplate, transactionTemplate);
  }

  @Test
  void ingestInsertsAnEntryThatRoundTripsExactly() {
    String runId = newRunAndId();
    ArtifactManifestEntry entry =
        entry(
            uniqueArtifactId("a"),
            runId,
            "test-1",
            "Some test",
            "step-1",
            ArtifactType.TRACE,
            2048);

    repository.ingest(List.of(entry));

    List<ArtifactManifestEntry> found = repository.findForRun(runId, null);
    assertThat(found).containsExactly(entry);
  }

  /**
   * [P1] fix - a genuine re-read of the exact same entry (the legitimate case: an incremental pass
   * and the final drain both seeing the same manifest line) must stay a silent no-op. Reusing the
   * exact same {@link ArtifactManifestEntry} instance (not just an equal one) makes the "identical"
   * half of this claim unambiguous - see {@link
   * #ingestRejectsTheSameArtifactIdWithDifferentMetadata} for the other half: the same id with
   * genuinely different metadata must now throw instead of silently winning or losing.
   */
  @Test
  void ingestIsIdempotentForAGenuinelyIdenticalReingest() {
    String runId = newRunAndId();
    ArtifactManifestEntry original =
        entry(
            uniqueArtifactId("a"),
            runId,
            "test-1",
            "Original name",
            null,
            ArtifactType.SCREENSHOT,
            1024);

    repository.ingest(List.of(original));
    assertThatCode(() -> repository.ingest(List.of(original))).doesNotThrowAnyException();

    List<ArtifactManifestEntry> found = repository.findForRun(runId, null);
    assertThat(found).containsExactly(original);
  }

  /**
   * [P1] fix - idempotency only actually applies to a byte-for-byte-identical re-read of the same
   * entry. A prior version of {@link JdbcArtifactRepository#ingest} used a bare {@code ON CONFLICT
   * (artifact_id) DO NOTHING}, which silently accepted this case too even though {@code
   * sizeBytes}/{@code testDisplayName} genuinely differ - durably losing the second write's real
   * metadata with no signal at all. Now it must throw instead.
   */
  @Test
  void ingestRejectsTheSameArtifactIdWithDifferentMetadata() {
    String runId = newRunAndId();
    String artifactId = uniqueArtifactId("a");
    ArtifactManifestEntry original =
        entry(artifactId, runId, "test-1", "Original name", null, ArtifactType.SCREENSHOT, 1024);
    ArtifactManifestEntry conflicting =
        entry(artifactId, runId, "test-1", "Original name", null, ArtifactType.SCREENSHOT, 9999);
    repository.ingest(List.of(original));

    assertThatThrownBy(() -> repository.ingest(List.of(conflicting)))
        .isInstanceOf(ArtifactIngestionConflictException.class);

    // The rejected write must not have overwritten the original row either.
    assertThat(repository.findForRun(runId, null)).containsExactly(original);
  }

  /**
   * Same conflict, from a different angle: the same id reused across two entirely different runs.
   */
  @Test
  void ingestRejectsTheSameArtifactIdUsedForADifferentRun() {
    String firstRunId = newRunAndId();
    String secondRunId = newRunAndId();
    String artifactId = uniqueArtifactId("a");
    ArtifactManifestEntry forFirstRun =
        entry(artifactId, firstRunId, "test-1", "Test 1", null, ArtifactType.SCREENSHOT, 1024);
    ArtifactManifestEntry forSecondRun =
        entry(artifactId, secondRunId, "test-1", "Test 1", null, ArtifactType.SCREENSHOT, 1024);
    repository.ingest(List.of(forFirstRun));

    assertThatThrownBy(() -> repository.ingest(List.of(forSecondRun)))
        .isInstanceOf(ArtifactIngestionConflictException.class);

    assertThat(repository.findForRun(secondRunId, null)).isEmpty();
  }

  /**
   * [P2] fix - the manifest writer records {@code Instant.now()} at nanosecond precision, but
   * {@code TIMESTAMPTZ} only stores microseconds; without normalizing before comparison, a
   * genuinely-identical re-ingest of an entry with real nanosecond precision would otherwise look
   * like a conflict purely from a precision difference that was never a real one. The earlier
   * whole-second-only test fixtures never actually exercised this - real manifest timestamps do
   * carry sub-microsecond digits.
   */
  @Test
  void ingestNormalizesNanosecondPrecisionCreatedAtSoAnIdenticalReingestIsStillANoOp() {
    String runId = newRunAndId();
    String artifactId = uniqueArtifactId("a");
    Instant nanoPrecision = Instant.parse("2026-01-01T00:00:00.123456789Z");
    Instant expectedTruncated = Instant.parse("2026-01-01T00:00:00.123456Z");
    ArtifactManifestEntry withNanoPrecision =
        new ArtifactManifestEntry(
            ArtifactManifestEntry.CURRENT_SCHEMA_VERSION,
            artifactId,
            runId,
            "test-1",
            "Some test",
            null,
            ArtifactType.SCREENSHOT,
            artifactId + ".png",
            "image/png",
            1024,
            nanoPrecision);

    repository.ingest(List.of(withNanoPrecision));
    assertThatCode(() -> repository.ingest(List.of(withNanoPrecision))).doesNotThrowAnyException();

    List<ArtifactManifestEntry> found = repository.findForRun(runId, null);
    assertThat(found)
        .extracting(ArtifactManifestEntry::createdAt)
        .containsExactly(expectedTruncated);
  }

  @Test
  void findForRunFiltersByTestId() {
    String runId = newRunAndId();
    String artifactIdForTest2 = uniqueArtifactId("b");
    ArtifactManifestEntry forTest1 =
        entry(
            uniqueArtifactId("a"), runId, "test-1", "Test 1", null, ArtifactType.SCREENSHOT, 1024);
    ArtifactManifestEntry forTest2 =
        entry(artifactIdForTest2, runId, "test-2", "Test 2", null, ArtifactType.SCREENSHOT, 1024);
    repository.ingest(List.of(forTest1, forTest2));

    List<ArtifactManifestEntry> found = repository.findForRun(runId, "test-2");

    assertThat(found)
        .extracting(ArtifactManifestEntry::artifactId)
        .containsExactly(artifactIdForTest2);
  }

  @Test
  void findForRunOrdersByCreatedAtThenArtifactId() {
    String runId = newRunAndId();
    Instant earlier = Instant.parse("2026-01-01T00:00:00Z");
    Instant later = Instant.parse("2026-01-01T00:00:05Z");
    // "a-"/"z-"/"m-" prefixes on a globally-unique artifactId still sort in the expected relative
    // order: string comparison is lexicographic, so the leading character alone ('a' < 'm' < 'z')
    // decides ordering regardless of whatever random suffix follows it.
    String idA = uniqueArtifactId("a");
    String idZ = uniqueArtifactId("z");
    String idM = uniqueArtifactId("m");
    ArtifactManifestEntry z = entryAt(idZ, runId, earlier);
    ArtifactManifestEntry a = entryAt(idA, runId, earlier);
    ArtifactManifestEntry m = entryAt(idM, runId, later);
    // Ingested out of order - the returned order must come from the query itself, not insertion
    // order.
    repository.ingest(List.of(m, z, a));

    List<ArtifactManifestEntry> found = repository.findForRun(runId, null);

    assertThat(found).extracting(ArtifactManifestEntry::artifactId).containsExactly(idA, idZ, idM);
  }

  @Test
  void findForRunReturnsEmptyForARunWithNoArtifacts() {
    String runId = newRunAndId();

    assertThat(repository.findForRun(runId, null)).isEmpty();
  }

  private static ArtifactManifestEntry entryAt(String artifactId, String runId, Instant createdAt) {
    return new ArtifactManifestEntry(
        ArtifactManifestEntry.CURRENT_SCHEMA_VERSION,
        artifactId,
        runId,
        "test-1",
        "Some test",
        null,
        ArtifactType.SCREENSHOT,
        artifactId + ".png",
        "image/png",
        1024,
        createdAt);
  }

  private static ArtifactManifestEntry entry(
      String artifactId,
      String runId,
      String testId,
      String testDisplayName,
      String stepId,
      ArtifactType type,
      long sizeBytes) {
    return new ArtifactManifestEntry(
        ArtifactManifestEntry.CURRENT_SCHEMA_VERSION,
        artifactId,
        runId,
        testId,
        testDisplayName,
        stepId,
        type,
        artifactId + extensionFor(type),
        mediaTypeFor(type),
        sizeBytes,
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static String extensionFor(ArtifactType type) {
    return switch (type) {
      case SCREENSHOT -> ".png";
      case TRACE -> ".zip";
      case VIDEO -> ".webm";
    };
  }

  private static String mediaTypeFor(ArtifactType type) {
    return switch (type) {
      case SCREENSHOT -> "image/png";
      case TRACE -> "application/zip";
      case VIDEO -> "video/webm";
    };
  }

  /**
   * {@code artifact_id} is the table's own primary key - globally unique, not scoped per run - and
   * every test method here shares one static Testcontainers Postgres/schema. A bare literal like
   * {@code "a"} reused across two different test methods would silently collide: the second test's
   * insert would hit {@code ON CONFLICT (artifact_id) DO NOTHING} against a leftover row from an
   * earlier, unrelated test - exactly the real bug this suffix exists to rule out (confirmed live:
   * two tests failed with an empty result before this fix was added).
   */
  private static String uniqueArtifactId(String label) {
    return label + "-" + UUID.randomUUID();
  }

  /**
   * Inserts a minimal parent {@code runs} row (required by {@code fk_artifacts_run}) and returns
   * its id.
   */
  private static String newRunAndId() {
    String runId = "run-" + UUID.randomUUID();
    jdbcTemplate.update(
        """
        INSERT INTO runs (run_id, environment, suite, status, requested_at)
        VALUES (?, 'PUBLIC', 'SMOKE', 'QUEUED', now())
        """,
        runId);
    return runId;
  }
}
