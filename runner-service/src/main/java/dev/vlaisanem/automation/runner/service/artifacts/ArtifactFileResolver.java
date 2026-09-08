package dev.vlaisanem.automation.runner.service.artifacts;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.service.exception.ArtifactManifestCorruptException;
import dev.vlaisanem.automation.runner.service.exception.ArtifactNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Safely resolves an {@link ArtifactManifestEntry}'s {@code relativePath} to a real filesystem
 * path, shared by both {@link ArtifactService}'s download path and {@link
 * ArtifactIngestionService}'s D4.2 size-consistency check - extracted so a second call site never
 * re-implements a naive {@code root.resolve(relativePath)} that would reopen the exact symlink
 * escape this class exists to close.
 *
 * <p>Never trusts {@code entry.relativePath()} alone, even though {@link ArtifactManifestEntry}'s
 * own compact constructor already rejects an absolute path or a {@code ..} segment - defense in
 * depth, for a value that ultimately came from a file on disk rather than from code that
 * constructed it directly. Two checks, not one: {@code normalize()} + {@code startsWith} alone
 * cannot catch a symlink planted inside the run's own artifacts directory that points somewhere
 * else entirely (the normalized path never leaves the run root textually, only once resolved
 * through the symlink does it), so the real, symlink-resolved path is checked against the real,
 * symlink-resolved run root too.
 */
final class ArtifactFileResolver {

  private ArtifactFileResolver() {}

  static Path resolve(Path artifactsRootDir, String runId, ArtifactManifestEntry entry) {
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
