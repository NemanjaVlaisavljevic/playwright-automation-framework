package dev.vlaisanem.automation.runner.service.artifacts;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.exception.ArtifactManifestCorruptException;
import dev.vlaisanem.automation.runner.service.exception.ArtifactNotFoundException;
import dev.vlaisanem.automation.runner.service.orchestration.RunService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Reads and safely resolves a run's artifacts. D2.4 - {@link #listForRun}/{@link #download} query
 * the {@code artifacts} table via {@link ArtifactRepository} exclusively; the manifest file itself
 * is no longer read here at all - {@link ArtifactIngestionService} is the sole component that still
 * reads it, to keep {@code artifacts} populated. See {@link #resolveFile} for this class's own
 * remaining trust boundary: an {@link ArtifactManifestEntry} read back from the database still
 * originated from the manifest file, so its {@code relativePath} is treated exactly as untrusted as
 * it always was.
 */
@Service
public class ArtifactService {

  private final RunService runService;
  private final ArtifactRepository repository;
  private final Path artifactsRootDir;

  public ArtifactService(
      RunService runService, ArtifactRepository repository, RunnerProperties properties) {
    this.runService = runService;
    this.repository = repository;
    this.artifactsRootDir = Path.of(properties.artifactsDir()).toAbsolutePath().normalize();
  }

  /**
   * {@code testIdFilter} narrows the result to one test's own artifacts when non-blank - a query
   * parameter, not a path variable: a real {@code testId} (JUnit's own unique-ID format) contains
   * {@code /} characters, which would make it an unusable REST path segment.
   */
  public List<ArtifactManifestEntry> listForRun(String runId, String testIdFilter) {
    // find() alone is what 404s for an unknown runId - the returned Run itself is otherwise unused
    // now that artifacts are read from Postgres directly, which needs no "is this run terminal"
    // tolerance the way reading a still-being-appended manifest file directly used to.
    runService.find(runId);
    return repository.findForRun(runId, testIdFilter);
  }

  /**
   * Whether {@code runId}'s artifact metadata may be incomplete because its final ingestion drain
   * failed and has not yet been recovered by {@code ArtifactIngestionService}'s own bounded
   * background reconciliation - lets {@link
   * dev.vlaisanem.automation.runner.service.api.ArtifactController} distinguish "this run genuinely
   * has zero artifacts" from "ingestion for this run has not finished yet" instead of conflating
   * the two (a review finding).
   */
  public boolean isIngestionIncomplete(String runId) {
    return repository.isIngestionIncomplete(runId);
  }

  public ArtifactDownload download(String runId, String artifactId) {
    ArtifactManifestEntry entry =
        listForRun(runId, null).stream()
            .filter(candidate -> artifactId.equals(candidate.artifactId()))
            .findFirst()
            .orElseThrow(() -> new ArtifactNotFoundException(runId, artifactId));
    return new ArtifactDownload(entry, resolveFile(runId, entry));
  }

  /**
   * Never trusts {@code entry.relativePath()} alone, even though {@link ArtifactManifestEntry}'s
   * own compact constructor already rejects an absolute path or a {@code ..} segment - defense in
   * depth, for a value that ultimately came from a file on disk rather than from code that
   * constructed it directly. Two checks, not one: {@code normalize()} + {@code startsWith} alone
   * cannot catch a symlink planted inside the run's own artifacts directory that points somewhere
   * else entirely (the normalized path never leaves the run root textually, only once resolved
   * through the symlink does it), so the real, symlink-resolved path is checked against the real,
   * symlink-resolved run root too.
   */
  private Path resolveFile(String runId, ArtifactManifestEntry entry) {
    Path runRoot = artifactsRootDir.resolve(runId);
    Path candidate = runRoot.resolve(entry.relativePath()).normalize();
    if (!candidate.startsWith(runRoot)) {
      throw new ArtifactManifestCorruptException(
          runId, "relativePath escapes the run's artifacts root: " + entry.relativePath());
    }
    Path realRunRoot;
    Path realCandidate;
    try {
      realRunRoot = runRoot.toRealPath();
      realCandidate = candidate.toRealPath();
    } catch (NoSuchFileException missing) {
      throw new ArtifactNotFoundException(runId, entry.artifactId());
    } catch (IOException e) {
      throw new ArtifactManifestCorruptException(
          runId, "could not resolve " + candidate + ": " + e.getMessage());
    }
    if (!realCandidate.startsWith(realRunRoot)) {
      throw new ArtifactManifestCorruptException(
          runId,
          "resolved artifact path escapes the run's artifacts root via a symlink: "
              + entry.relativePath());
    }
    // toRealPath()+startsWith alone would happily accept a directory, a FIFO, or any other
    // non-regular filesystem object sitting where the manifest claims a file exists - that would
    // only surface later as a confusing failure trying to actually read it as a resource.
    if (!Files.isRegularFile(realCandidate, LinkOption.NOFOLLOW_LINKS)) {
      throw new ArtifactManifestCorruptException(
          runId, "resolved artifact path is not a regular file: " + realCandidate);
    }
    return realCandidate;
  }
}
