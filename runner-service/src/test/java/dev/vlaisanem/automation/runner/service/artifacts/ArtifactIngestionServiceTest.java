package dev.vlaisanem.automation.runner.service.artifacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.contract.ArtifactType;
import dev.vlaisanem.automation.runner.service.config.RateLimitRule;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D2.4 - direct unit coverage for {@link ArtifactIngestionService}: the manifest-reading,
 * idempotent -insert, never-throws contract every hot-path caller ({@code RunEventBroker#append},
 * {@code RunLifecycleCoordinator#finishIfLive}) depends on.
 */
class ArtifactIngestionServiceTest {

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final String RUN_ID = "run-1";

  @Test
  void ingestsEveryEntryFromTheManifestFile(@TempDir Path artifactsRoot) throws IOException {
    writeManifest(artifactsRoot, entry("a", "test-1"), entry("b", "test-2"));
    FakeArtifactRepository repository = new FakeArtifactRepository();
    ArtifactIngestionService service = serviceFor(artifactsRoot, repository);

    service.ingestAvailableEntries(RUN_ID, false);

    assertThat(repository.findForRun(RUN_ID, null))
        .extracting(ArtifactManifestEntry::artifactId)
        .containsExactly("a", "b");
  }

  @Test
  void reingestingTheSameManifestIsIdempotent(@TempDir Path artifactsRoot) throws IOException {
    writeManifest(artifactsRoot, entry("a", "test-1"));
    FakeArtifactRepository repository = new FakeArtifactRepository();
    ArtifactIngestionService service = serviceFor(artifactsRoot, repository);

    service.ingestAvailableEntries(RUN_ID, false);
    service.ingestAvailableEntries(RUN_ID, false); // the incremental pass and the final drain
    // may legitimately both see the same manifest lines.

    assertThat(repository.findForRun(RUN_ID, null))
        .extracting(ArtifactManifestEntry::artifactId)
        .containsExactly("a");
  }

  @Test
  void doesNothingWhenNoManifestFileExistsYet(@TempDir Path artifactsRoot) {
    FakeArtifactRepository repository = new FakeArtifactRepository();
    ArtifactIngestionService service = serviceFor(artifactsRoot, repository);

    assertThatCode(() -> service.ingestAvailableEntries(RUN_ID, false)).doesNotThrowAnyException();

    assertThat(repository.findForRun(RUN_ID, null)).isEmpty();
  }

  /**
   * The whole point of this service's own contract: a corrupt manifest (here, an unterminated
   * trailing line reported once {@code runTerminal} is {@code true} - see {@code
   * ArtifactManifestReader}) must never propagate out and fail whatever lifecycle event triggered
   * ingestion. {@code artifacts} is a derived index, not a source of truth - losing one pass is
   * always recoverable later.
   */
  @Test
  void swallowsAManifestCorruptionFailureRatherThanThrowing(@TempDir Path artifactsRoot)
      throws IOException {
    Path runRoot = artifactsRoot.resolve(RUN_ID);
    Files.createDirectories(runRoot);
    Files.write(
        runRoot.resolve("manifest.jsonl"),
        "{ this is not even valid JSON and has no trailing newline"
            .getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE);
    FakeArtifactRepository repository = new FakeArtifactRepository();
    ArtifactIngestionService service = serviceFor(artifactsRoot, repository);

    assertThatCode(() -> service.ingestAvailableEntries(RUN_ID, true)).doesNotThrowAnyException();

    assertThat(repository.findForRun(RUN_ID, null)).isEmpty();
  }

  @Test
  void ingestAvailableEntriesReturnsSucceededOnAGoodManifest(@TempDir Path artifactsRoot)
      throws IOException {
    writeManifest(artifactsRoot, entry("a", "test-1"));
    ArtifactIngestionService service = serviceFor(artifactsRoot, new FakeArtifactRepository());

    assertThat(service.ingestAvailableEntries(RUN_ID, false))
        .isEqualTo(ArtifactIngestionOutcome.SUCCEEDED);
  }

  @Test
  void ingestAvailableEntriesReturnsFailedOnACorruptManifest(@TempDir Path artifactsRoot)
      throws IOException {
    writeCorruptManifest(artifactsRoot);

    assertThat(
            serviceFor(artifactsRoot, new FakeArtifactRepository())
                .ingestAvailableEntries(RUN_ID, true))
        .isEqualTo(ArtifactIngestionOutcome.FAILED);
  }

  /**
   * [P1] fix - a failed final drain used to be logged and entirely forgotten: the run still reached
   * its terminal status, but nothing durable recorded that its artifact metadata might be
   * incomplete, and nothing ever retried it.
   */
  @Test
  void aFailedFinalDrainMarksTheRunIncomplete(@TempDir Path artifactsRoot) throws IOException {
    writeCorruptManifest(artifactsRoot);
    FakeArtifactRepository repository = new FakeArtifactRepository();
    ArtifactIngestionService service = serviceFor(artifactsRoot, repository);

    service.ingestAvailableEntries(RUN_ID, true);

    assertThat(repository.isIngestionIncomplete(RUN_ID)).isTrue();
  }

  /**
   * The incremental path never touches the flag - only the final ({@code runTerminal}) drain does.
   */
  @Test
  void aFailedIncrementalPassDoesNotMarkTheRunIncomplete(@TempDir Path artifactsRoot)
      throws IOException {
    writeCorruptManifest(artifactsRoot);
    FakeArtifactRepository repository = new FakeArtifactRepository();
    ArtifactIngestionService service = serviceFor(artifactsRoot, repository);

    service.ingestAvailableEntries(RUN_ID, false);

    assertThat(repository.isIngestionIncomplete(RUN_ID)).isFalse();
  }

  @Test
  void aSuccessfulReconciliationPassClearsTheIncompleteFlag(@TempDir Path artifactsRoot)
      throws IOException {
    writeCorruptManifest(artifactsRoot);
    FakeArtifactRepository repository = new FakeArtifactRepository();
    ArtifactIngestionService service = serviceFor(artifactsRoot, repository);
    service.ingestAvailableEntries(RUN_ID, true);
    assertThat(repository.isIngestionIncomplete(RUN_ID)).isTrue();

    // The underlying problem is fixed before the next reconciliation attempt - mirrors, e.g., disk
    // space freeing up or a transient database blip resolving on its own.
    Files.delete(artifactsRoot.resolve(RUN_ID).resolve("manifest.jsonl"));
    writeManifest(artifactsRoot, entry("a", "test-1"));

    service.reconcileIncompleteRunsOnce();

    assertThat(repository.isIngestionIncomplete(RUN_ID)).isFalse();
    assertThat(repository.findForRun(RUN_ID, null))
        .extracting(ArtifactManifestEntry::artifactId)
        .containsExactly("a");
  }

  @Test
  void reconciliationStopsRetryingAfterTheBoundedAttemptCap(@TempDir Path artifactsRoot)
      throws IOException {
    writeCorruptManifest(artifactsRoot); // never fixed - this run can never actually recover
    FakeArtifactRepository repository = new FakeArtifactRepository();
    ArtifactIngestionService service = serviceFor(artifactsRoot, repository);
    service.ingestAvailableEntries(RUN_ID, true);

    for (int i = 0; i < 10; i++) {
      service.reconcileIncompleteRunsOnce();
    }

    assertThat(repository.isIngestionIncomplete(RUN_ID))
        .as("a manifest that will never recover must stay flagged, not silently clear")
        .isTrue();
    assertThat(service.reconciliationAttemptsFor(RUN_ID))
        .as("reconciliation must stop retrying at the bounded cap, not keep going forever")
        .isEqualTo(5);
  }

  /**
   * D4.2 - because the producer already deletes an oversized artifact before ever recording it in
   * the manifest, a real/manifested size mismatch here can only be a genuine anomaly (bug, race,
   * tampering) - treated exactly like any other manifest corruption: the whole pass fails, nothing
   * from it is ingested.
   */
  @Test
  void treatsARealSizeMismatchAgainstTheManifestAsCorruption(@TempDir Path artifactsRoot)
      throws IOException {
    ArtifactManifestEntry entry = entry("a", "test-1");
    Path runRoot = artifactsRoot.resolve(RUN_ID);
    Files.createDirectories(runRoot);
    Files.write(
        runRoot.resolve("manifest.jsonl"),
        (OBJECT_MAPPER.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8));
    // Real file deliberately a different size than the manifest's own sizeBytes() claim.
    Files.write(runRoot.resolve(entry.relativePath()), new byte[1]);
    FakeArtifactRepository repository = new FakeArtifactRepository();
    ArtifactIngestionService service = serviceFor(artifactsRoot, repository);

    assertThat(service.ingestAvailableEntries(RUN_ID, true))
        .isEqualTo(ArtifactIngestionOutcome.FAILED);
    assertThat(repository.findForRun(RUN_ID, null)).isEmpty();
  }

  /**
   * D4.2 - the producer is a genuine trust boundary, not a guarantee runner-service can rely on
   * alone: a file whose real size agrees with its own manifest entry (so the consistency check
   * above would not catch it) but exceeds the configured per-artifact limit must still be rejected
   * independently - a producer bug or a bypassed/older client must never let an oversized file
   * reach the served index just because its own manifest entry happens to agree with it.
   */
  @Test
  void rejectsAnArtifactWhoseRealSizeAgreesWithTheManifestButExceedsTheConfiguredLimit(
      @TempDir Path artifactsRoot) throws IOException {
    ArtifactManifestEntry entry =
        new ArtifactManifestEntry(
            ArtifactManifestEntry.CURRENT_SCHEMA_VERSION,
            "a",
            RUN_ID,
            "test-1",
            "display name for test-1",
            null,
            ArtifactType.SCREENSHOT,
            "a.png",
            "image/png",
            2000,
            Instant.parse("2026-01-01T00:00:00Z"));
    Path runRoot = artifactsRoot.resolve(RUN_ID);
    Files.createDirectories(runRoot);
    Files.write(
        runRoot.resolve("manifest.jsonl"),
        (OBJECT_MAPPER.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8));
    Files.write(runRoot.resolve(entry.relativePath()), new byte[2000]);
    FakeArtifactRepository repository = new FakeArtifactRepository();
    ArtifactIngestionService service = serviceFor(artifactsRoot, repository, 1024L);

    assertThat(service.ingestAvailableEntries(RUN_ID, true))
        .isEqualTo(ArtifactIngestionOutcome.FAILED);
    assertThat(repository.findForRun(RUN_ID, null)).isEmpty();
  }

  private static void writeCorruptManifest(Path artifactsRoot) throws IOException {
    Path runRoot = artifactsRoot.resolve(RUN_ID);
    Files.createDirectories(runRoot);
    Files.write(
        runRoot.resolve("manifest.jsonl"),
        "{ this is not even valid JSON and has no trailing newline"
            .getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE);
  }

  private static ArtifactIngestionService serviceFor(
      Path artifactsRoot, FakeArtifactRepository repository) {
    return new ArtifactIngestionService(
        OBJECT_MAPPER, repository, propertiesWithArtifactsDir(artifactsRoot));
  }

  private static ArtifactIngestionService serviceFor(
      Path artifactsRoot, FakeArtifactRepository repository, long artifactMaxBytes) {
    return new ArtifactIngestionService(
        OBJECT_MAPPER, repository, propertiesWithArtifactsDir(artifactsRoot, artifactMaxBytes));
  }

  private static RunnerProperties propertiesWithArtifactsDir(Path artifactsRoot) {
    return propertiesWithArtifactsDir(artifactsRoot, 26_214_400L);
  }

  private static RunnerProperties propertiesWithArtifactsDir(
      Path artifactsRoot, long artifactMaxBytes) {
    return new RunnerProperties(
        ".",
        Duration.ofMinutes(10),
        artifactsRoot.resolve("raw").toString(),
        artifactsRoot.resolve("logs").toString(),
        "src/test/resources/catalog/public-test-catalog.json",
        artifactsRoot.toString(),
        1024 * 1024,
        Duration.ofSeconds(5),
        Duration.ofSeconds(1),
        1,
        Duration.ofMillis(150),
        Duration.ofSeconds(5),
        10_000,
        Duration.ofSeconds(15),
        Duration.ofMinutes(10),
        new RateLimitRule(5, Duration.ofMinutes(1)),
        new RateLimitRule(10, Duration.ofMinutes(1)),
        new RateLimitRule(3, Duration.ofMinutes(1)),
        new RateLimitRule(10, Duration.ofHours(1)),
        new RateLimitRule(10, Duration.ofMinutes(1)),
        new RateLimitRule(120, Duration.ofMinutes(1)),
        new RateLimitRule(30, Duration.ofMinutes(1)),
        3,
        16384,
        Duration.ofDays(30),
        500,
        Duration.ofDays(14),
        Duration.ofHours(1),
        new RateLimitRule(10, Duration.ofHours(1)),
        1_048_576L,
        artifactMaxBytes,
        209_715_200L,
        2_097_152L,
        2_097_152L,
        104_857_600L,
        new RateLimitRule(10, Duration.ofHours(1)),
        Duration.ofSeconds(60));
  }

  private static ArtifactManifestEntry entry(String artifactId, String testId) {
    return new ArtifactManifestEntry(
        ArtifactManifestEntry.CURRENT_SCHEMA_VERSION,
        artifactId,
        RUN_ID,
        testId,
        "display name for " + testId,
        null,
        ArtifactType.SCREENSHOT,
        artifactId + ".png",
        "image/png",
        1024,
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  /**
   * Also writes a real file at each entry's own {@code relativePath}, exactly {@code sizeBytes()}
   * long - D4.2's ingestion-side consistency check now stats the real resolved file and compares it
   * against the manifest's own claim, so a fixture manifest entry with no matching real file (or a
   * mismatched size) would itself now be treated as corruption.
   */
  private static void writeManifest(Path artifactsRoot, ArtifactManifestEntry... entries)
      throws IOException {
    Path runRoot = artifactsRoot.resolve(RUN_ID);
    Files.createDirectories(runRoot);
    Path manifest = runRoot.resolve("manifest.jsonl");
    StringBuilder content = new StringBuilder();
    for (ArtifactManifestEntry entry : entries) {
      content.append(OBJECT_MAPPER.writeValueAsString(entry)).append('\n');
      Files.write(runRoot.resolve(entry.relativePath()), new byte[(int) entry.sizeBytes()]);
    }
    Files.write(
        manifest,
        content.toString().getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE);
  }
}
