package dev.vlaisanem.automation.runner.service.artifacts;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.service.exception.ArtifactManifestCorruptException;
import dev.vlaisanem.automation.runner.service.exception.ArtifactNotFoundException;
import dev.vlaisanem.automation.runner.service.filesystem.RunFilePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Safely resolves an {@link ArtifactManifestEntry}'s {@code relativePath} to a real filesystem
 * path, shared by {@link ArtifactService}'s download path and {@link ArtifactIngestionService}'s
 * size-consistency check.
 *
 * <p>Checks both the normalized path and the symlink-resolved real path against the run root:
 * {@code normalize()} + {@code startsWith} alone can't catch a symlink planted inside the run's
 * artifacts directory that points elsewhere, since the path only leaves the run root once resolved
 * through the symlink.
 */
final class ArtifactFileResolver {

  private ArtifactFileResolver() {}

  static Path resolve(Path artifactsRootDir, String runId, ArtifactManifestEntry entry) {
    Path normalizedArtifactsRoot = artifactsRootDir.toAbsolutePath().normalize();
    Path runRoot = RunFilePaths.artifactsDirectory(normalizedArtifactsRoot, runId);
    if (Files.isSymbolicLink(runRoot)) {
      throw new ArtifactManifestCorruptException(
          runId, "run artifacts directory must not be a symbolic link: " + runRoot);
    }
    Path realArtifactsRoot;
    Path realRunRoot;
    try {
      realArtifactsRoot = normalizedArtifactsRoot.toRealPath();
      realRunRoot = runRoot.toRealPath();
    } catch (NoSuchFileException missing) {
      throw new ArtifactNotFoundException(runId, entry.artifactId());
    } catch (IOException e) {
      throw new ArtifactManifestCorruptException(
          runId, "could not resolve " + runRoot + ": " + e.getMessage());
    }
    if (!realRunRoot.startsWith(realArtifactsRoot)
        || !Files.isDirectory(realRunRoot, LinkOption.NOFOLLOW_LINKS)) {
      throw new ArtifactManifestCorruptException(
          runId, "run artifacts directory resolves outside the configured artifacts root");
    }
    Path candidate = realRunRoot.resolve(entry.relativePath()).normalize();
    if (!candidate.startsWith(realRunRoot)) {
      throw new ArtifactManifestCorruptException(
          runId, "relativePath escapes the run's artifacts root: " + entry.relativePath());
    }
    Path realCandidate;
    try {
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
