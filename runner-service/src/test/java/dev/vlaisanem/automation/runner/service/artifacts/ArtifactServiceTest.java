package dev.vlaisanem.automation.runner.service.artifacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.contract.ArtifactType;
import dev.vlaisanem.automation.runner.service.config.RateLimitRule;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.exception.ArtifactManifestCorruptException;
import dev.vlaisanem.automation.runner.service.exception.ArtifactNotFoundException;
import dev.vlaisanem.automation.runner.service.orchestration.RunService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D2.4 - rewritten against {@link FakeArtifactRepository}: {@link ArtifactService} now reads
 * exclusively from {@link ArtifactRepository}, never the manifest file directly (that is {@link
 * ArtifactIngestionService}'s own, separately-tested job). Tests that exercise {@link
 * ArtifactService#download}'s filesystem-safety checks (symlink escape, non-regular file, missing
 * file) still write real files to disk - that trust boundary is unchanged - but seed the
 * corresponding {@link ArtifactManifestEntry} into the fake repository instead of writing a
 * manifest line for it.
 */
class ArtifactServiceTest {

  private static final String RUN_ID = "run-1";

  @Test
  void listsEveryEntryForARunningRun(@TempDir Path artifactsRoot) {
    ArtifactService service =
        serviceFor(artifactsRoot, RunStatus.RUNNING, entry("a", "test-1"), entry("b", "test-2"));

    List<ArtifactManifestEntry> entries = service.listForRun(RUN_ID, null);

    assertThat(entries).extracting(ArtifactManifestEntry::artifactId).containsExactly("a", "b");
  }

  @Test
  void filtersByTestIdWhenGiven(@TempDir Path artifactsRoot) {
    ArtifactService service =
        serviceFor(artifactsRoot, RunStatus.RUNNING, entry("a", "test-1"), entry("b", "test-2"));

    List<ArtifactManifestEntry> entries = service.listForRun(RUN_ID, "test-2");

    assertThat(entries).extracting(ArtifactManifestEntry::artifactId).containsExactly("b");
  }

  @Test
  void downloadResolvesTheRealFileForAKnownArtifactId(@TempDir Path artifactsRoot)
      throws IOException {
    Path runRoot = artifactsRoot.resolve(RUN_ID);
    Files.createDirectories(runRoot);
    Files.writeString(runRoot.resolve("a.png"), "fake png bytes");
    ArtifactService service = serviceFor(artifactsRoot, RunStatus.SUCCEEDED, entry("a", "test-1"));

    ArtifactDownload download = service.download(RUN_ID, "a");

    assertThat(download.entry().artifactId()).isEqualTo("a");
    assertThat(download.file()).hasContent("fake png bytes");
  }

  @Test
  void downloadThrowsWhenTheArtifactIdIsUnknown(@TempDir Path artifactsRoot) {
    ArtifactService service = serviceFor(artifactsRoot, RunStatus.SUCCEEDED, entry("a", "test-1"));

    assertThatThrownBy(() -> service.download(RUN_ID, "does-not-exist"))
        .isInstanceOf(ArtifactNotFoundException.class);
  }

  @Test
  void downloadThrowsWhenTheManifestedFileDoesNotActuallyExistOnDisk(@TempDir Path artifactsRoot) {
    // The repository has an entry for a.png, but no such file was ever written to disk.
    ArtifactService service = serviceFor(artifactsRoot, RunStatus.SUCCEEDED, entry("a", "test-1"));

    assertThatThrownBy(() -> service.download(RUN_ID, "a"))
        .isInstanceOf(ArtifactNotFoundException.class);
  }

  /**
   * Regression test for a review's finding: {@code toRealPath()} plus the {@code startsWith}
   * containment check both happily accept a directory (or any other non-regular filesystem object)
   * sitting where the manifest claims a file exists - without this check, that would only surface
   * later as a confusing failure trying to actually read it as an HTTP resource.
   */
  @Test
  void downloadThrowsWhenTheManifestedPathIsADirectoryNotAFile(@TempDir Path artifactsRoot)
      throws IOException {
    Path runRoot = artifactsRoot.resolve(RUN_ID);
    Files.createDirectories(runRoot.resolve("a.png"));
    ArtifactService service = serviceFor(artifactsRoot, RunStatus.SUCCEEDED, entry("a", "test-1"));

    assertThatThrownBy(() -> service.download(RUN_ID, "a"))
        .isInstanceOf(ArtifactManifestCorruptException.class)
        .satisfies(
            exception ->
                assertThat(((ArtifactManifestCorruptException) exception).diagnosticReason())
                    .contains("not a regular file"));
  }

  /**
   * Proves the review's specific concern: a symlink planted inside the run's own artifacts
   * directory, pointing outside it, must not be served even though the manifest's own {@code
   * relativePath} textually never leaves the run root (only resolving through the symlink reveals
   * that). Skipped, not failed, where this process cannot create a symlink at all - creating one on
   * Windows needs Developer Mode or an elevated process, confirmed unavailable on this machine;
   * Linux CI runs this for real.
   */
  @Test
  void refusesToServeAFileReachedThroughASymlinkEscapingTheRunRoot(@TempDir Path artifactsRoot)
      throws IOException {
    Path runRoot = artifactsRoot.resolve(RUN_ID);
    Files.createDirectories(runRoot);
    Path secretOutsideTheRunRoot = artifactsRoot.resolve("secret.txt");
    Files.writeString(secretOutsideTheRunRoot, "top secret, not this run's own artifact");
    Path linkInsideRunRoot = runRoot.resolve("escape.png");
    try {
      Files.createSymbolicLink(linkInsideRunRoot, secretOutsideTheRunRoot);
    } catch (UnsupportedOperationException | IOException cannotCreateSymlink) {
      Assumptions.abort(
          "Symbolic links are not supported/permitted in this environment: "
              + cannotCreateSymlink.getMessage());
      return;
    }
    ArtifactService service =
        serviceFor(artifactsRoot, RunStatus.SUCCEEDED, entry("escape", "test-1", "escape.png"));

    assertThatThrownBy(() -> service.download(RUN_ID, "escape"))
        .isInstanceOf(ArtifactManifestCorruptException.class)
        .satisfies(
            exception ->
                assertThat(((ArtifactManifestCorruptException) exception).diagnosticReason())
                    .contains("symlink"));
  }

  private static ArtifactService serviceFor(
      Path artifactsRoot, RunStatus status, ArtifactManifestEntry... entries) {
    RunService runService = mock(RunService.class);
    when(runService.find(RUN_ID)).thenReturn(runWithStatus(status));
    FakeArtifactRepository repository = new FakeArtifactRepository();
    repository.ingest(List.of(entries));
    return new ArtifactService(runService, repository, propertiesWithArtifactsDir(artifactsRoot));
  }

  private static Run runWithStatus(RunStatus status) {
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
    Run run = Run.queued(RUN_ID, Environment.PUBLIC, Suite.SMOKE, requestedAt);
    if (status == RunStatus.QUEUED) {
      return run;
    }
    run = run.transitionTo(RunStatus.STARTING, requestedAt);
    if (status == RunStatus.STARTING) {
      return run;
    }
    run = run.transitionTo(RunStatus.RUNNING, requestedAt.plusSeconds(1));
    if (status == RunStatus.RUNNING) {
      return run;
    }
    return run.transitionTo(status, requestedAt.plusSeconds(2), 0, null);
  }

  private static RunnerProperties propertiesWithArtifactsDir(Path artifactsRoot) {
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
        26_214_400L,
        209_715_200L,
        2_097_152L,
        2_097_152L,
        104_857_600L,
        new RateLimitRule(10, Duration.ofHours(1)),
        Duration.ofSeconds(60));
  }

  private static ArtifactManifestEntry entry(String artifactId, String testId) {
    return entry(artifactId, testId, artifactId + ".png");
  }

  private static ArtifactManifestEntry entry(
      String artifactId, String testId, String relativePath) {
    return new ArtifactManifestEntry(
        ArtifactManifestEntry.CURRENT_SCHEMA_VERSION,
        artifactId,
        RUN_ID,
        testId,
        "display name for " + testId,
        null,
        ArtifactType.SCREENSHOT,
        relativePath,
        "image/png",
        1024,
        Instant.parse("2026-01-01T00:00:00Z"));
  }
}
