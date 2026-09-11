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
 * Verifies {@link JdbcArtifactRepository} against a real Postgres: fields round-trip exactly,
 * {@code ingest} is a no-op on an identical re-ingest, and {@code findForRun} filters/orders
 * deterministically. Each entry requires a parent {@code runs} row ({@code fk_artifacts_run}).
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
   * A genuine re-read of the exact same entry (e.g. an incremental pass then the final drain) must
   * stay a silent no-op. See {@link #ingestRejectsTheSameArtifactIdWithDifferentMetadata} for the
   * differing-metadata case.
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
   * Idempotency applies only to a byte-for-byte-identical re-read; the same id with different
   * metadata must throw rather than silently overwrite or lose the second write.
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
   * {@code TIMESTAMPTZ} stores only microsecond precision; nanosecond timestamps must be normalized
   * before comparison, or an identical re-ingest could look like a false conflict.
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
    // Prefixes sort lexicographically ('a' < 'm' < 'z') regardless of the random UUID suffix.
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
   * {@code artifact_id} is globally unique and every test method shares one static schema, so a
   * bare literal would collide across tests via {@code ON CONFLICT DO NOTHING}; the random suffix
   * avoids that.
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
