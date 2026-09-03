package dev.vlaisanem.automation.runner.service.artifacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.contract.ArtifactType;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.exception.ArtifactManifestCorruptException;
import dev.vlaisanem.automation.runner.service.exception.ArtifactNotFoundException;
import dev.vlaisanem.automation.runner.service.orchestration.RunService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactServiceTest {

  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final String RUN_ID = "run-1";

  @Test
  void listsEveryEntryForARunningRun(@TempDir Path artifactsRoot) throws IOException {
    writeManifest(artifactsRoot, entry("a", "test-1"), entry("b", "test-2"));
    ArtifactService service = serviceFor(artifactsRoot, RunStatus.RUNNING);

    List<ArtifactManifestEntry> entries = service.listForRun(RUN_ID, null);

    assertThat(entries).extracting(ArtifactManifestEntry::artifactId).containsExactly("a", "b");
  }

  @Test
  void filtersByTestIdWhenGiven(@TempDir Path artifactsRoot) throws IOException {
    writeManifest(artifactsRoot, entry("a", "test-1"), entry("b", "test-2"));
    ArtifactService service = serviceFor(artifactsRoot, RunStatus.RUNNING);

    List<ArtifactManifestEntry> entries = service.listForRun(RUN_ID, "test-2");

    assertThat(entries).extracting(ArtifactManifestEntry::artifactId).containsExactly("b");
  }

  @Test
  void downloadResolvesTheRealFileForAKnownArtifactId(@TempDir Path artifactsRoot)
      throws IOException {
    Path runRoot = artifactsRoot.resolve(RUN_ID);
    Files.createDirectories(runRoot);
    Files.writeString(runRoot.resolve("a.png"), "fake png bytes");
    writeManifest(artifactsRoot, entry("a", "test-1"));
    ArtifactService service = serviceFor(artifactsRoot, RunStatus.SUCCEEDED);

    ArtifactDownload download = service.download(RUN_ID, "a");

    assertThat(download.entry().artifactId()).isEqualTo("a");
    assertThat(download.file()).hasContent("fake png bytes");
  }

  @Test
  void downloadThrowsWhenTheArtifactIdIsUnknown(@TempDir Path artifactsRoot) throws IOException {
    writeManifest(artifactsRoot, entry("a", "test-1"));
    ArtifactService service = serviceFor(artifactsRoot, RunStatus.SUCCEEDED);

    assertThatThrownBy(() -> service.download(RUN_ID, "does-not-exist"))
        .isInstanceOf(ArtifactNotFoundException.class);
  }

  @Test
  void downloadThrowsWhenTheManifestedFileDoesNotActuallyExistOnDisk(@TempDir Path artifactsRoot)
      throws IOException {
    // Manifest references a.png, but no such file was ever written to disk.
    writeManifest(artifactsRoot, entry("a", "test-1"));
    ArtifactService service = serviceFor(artifactsRoot, RunStatus.SUCCEEDED);

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
    writeManifest(artifactsRoot, entry("a", "test-1"));
    ArtifactService service = serviceFor(artifactsRoot, RunStatus.SUCCEEDED);

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
    writeManifest(artifactsRoot, entry("escape", "test-1", "escape.png"));
    ArtifactService service = serviceFor(artifactsRoot, RunStatus.SUCCEEDED);

    assertThatThrownBy(() -> service.download(RUN_ID, "escape"))
        .isInstanceOf(ArtifactManifestCorruptException.class)
        .satisfies(
            exception ->
                assertThat(((ArtifactManifestCorruptException) exception).diagnosticReason())
                    .contains("symlink"));
  }

  private static ArtifactService serviceFor(Path artifactsRoot, RunStatus status) {
    RunService runService = mock(RunService.class);
    when(runService.find(RUN_ID)).thenReturn(runWithStatus(status));
    return new ArtifactService(
        runService, OBJECT_MAPPER, propertiesWithArtifactsDir(artifactsRoot));
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
        artifactsRoot.resolve("journal").toString(),
        artifactsRoot.resolve("logs").toString(),
        artifactsRoot.toString(),
        1024 * 1024,
        Duration.ofSeconds(5),
        Duration.ofSeconds(1),
        1,
        Duration.ofMillis(150),
        Duration.ofSeconds(5),
        10_000,
        Duration.ofSeconds(15),
        Duration.ofMinutes(10));
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

  private static void writeManifest(Path artifactsRoot, ArtifactManifestEntry... entries)
      throws IOException {
    Path runRoot = artifactsRoot.resolve(RUN_ID);
    Files.createDirectories(runRoot);
    Path manifest = runRoot.resolve("manifest.jsonl");
    StringBuilder content = new StringBuilder();
    for (ArtifactManifestEntry entry : entries) {
      content.append(OBJECT_MAPPER.writeValueAsString(entry)).append('\n');
    }
    Files.write(
        manifest,
        content.toString().getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE);
  }
}
