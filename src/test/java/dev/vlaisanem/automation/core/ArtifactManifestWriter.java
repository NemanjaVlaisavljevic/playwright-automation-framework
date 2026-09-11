package dev.vlaisanem.automation.core;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.contract.ArtifactType;
import dev.vlaisanem.automation.support.JsonSupport;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Appends one {@link ArtifactManifestEntry} JSON Line per artifact to {@code manifest.jsonl} in the
 * run's artifacts root.
 *
 * <p>Locks in two layers: an in-JVM {@code synchronized} lock (an OS {@link FileLock} would throw
 * {@code OverlappingFileLockException} across threads of the same JVM rather than block) plus an
 * OS-level {@link FileLock} that protects separate JVM processes from interleaving. The file's size
 * is read only once the {@link FileLock} is held, to avoid overwriting a concurrent append.
 */
final class ArtifactManifestWriter {

  private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactManifestWriter.class);
  private static final ConcurrentHashMap<Path, Object> LOCKS_BY_MANIFEST_PATH =
      new ConcurrentHashMap<>();

  private ArtifactManifestWriter() {}

  /**
   * Builds and appends the manifest entry, unless {@code artifactFile} exceeds {@code
   * artifactMaxBytes} or a manifest/per-run budget in {@link #append} rejects it - either way
   * {@code artifactFile} is deleted rather than left orphaned on disk.
   *
   * @return {@code true} if the entry was actually recorded in the manifest.
   */
  static boolean record(
      Path artifactsRoot,
      String runId,
      String testId,
      String testDisplayName,
      String stepId,
      ArtifactType type,
      Path artifactFile,
      String mediaType,
      long artifactMaxBytes,
      long runMaxTotalArtifactBytes,
      long manifestMaxBytes)
      throws IOException {
    long actualSize = Files.size(artifactFile);
    if (actualSize > artifactMaxBytes) {
      LOGGER.warn(
          "Deleting artifact {} ({} bytes) - exceeds the configured {}-byte per-artifact limit",
          artifactFile,
          actualSize,
          artifactMaxBytes);
      Files.deleteIfExists(artifactFile);
      return false;
    }
    String relativePath = artifactsRoot.relativize(artifactFile).toString().replace('\\', '/');
    ArtifactManifestEntry entry =
        new ArtifactManifestEntry(
            ArtifactManifestEntry.CURRENT_SCHEMA_VERSION,
            UUID.randomUUID().toString(),
            runId,
            testId,
            testDisplayName,
            stepId,
            type,
            relativePath,
            mediaType,
            actualSize,
            Instant.now());
    boolean recorded = append(artifactsRoot, entry, runMaxTotalArtifactBytes, manifestMaxBytes);
    if (!recorded) {
      Files.deleteIfExists(artifactFile);
    }
    return recorded;
  }

  /**
   * @return {@code true} if appended; {@code false} if the manifest-size or run-total-bytes budget
   *     rejected it. The run total is computed fresh under the lock, not cached, since a cache
   *     would not hold across separate JVM processes.
   */
  private static boolean append(
      Path artifactsRoot,
      ArtifactManifestEntry entry,
      long runMaxTotalArtifactBytes,
      long manifestMaxBytes)
      throws IOException {
    Path manifestFile = artifactsRoot.resolve("manifest.jsonl").toAbsolutePath().normalize();
    byte[] line = (JsonSupport.write(entry) + "\n").getBytes(StandardCharsets.UTF_8);
    Object inProcessLock =
        LOCKS_BY_MANIFEST_PATH.computeIfAbsent(manifestFile, unused -> new Object());
    synchronized (inProcessLock) {
      Files.createDirectories(manifestFile.getParent());
      try (FileChannel channel =
              FileChannel.open(manifestFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          FileLock fileLock = channel.lock()) {
        long manifestSizeSoFar = channel.size();
        if (manifestSizeSoFar + line.length > manifestMaxBytes) {
          LOGGER.warn(
              "Refusing to append to {} - would grow past the configured {}-byte manifest limit",
              manifestFile,
              manifestMaxBytes);
          return false;
        }
        // Already includes the new artifact's own bytes, since it's written to disk before this
        // runs.
        long runArtifactTotal = directorySize(artifactsRoot);
        if (runArtifactTotal > runMaxTotalArtifactBytes) {
          LOGGER.warn(
              "Refusing to append to {} - run's total artifact bytes ({}) would exceed the"
                  + " configured {}-byte per-run limit",
              manifestFile,
              runArtifactTotal,
              runMaxTotalArtifactBytes);
          return false;
        }
        channel.position(manifestSizeSoFar);
        ByteBuffer buffer = ByteBuffer.wrap(line);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        return true;
      }
    }
  }

  private static long directorySize(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return 0L;
    }
    AtomicLong total = new AtomicLong();
    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            total.addAndGet(attrs.size());
            return FileVisitResult.CONTINUE;
          }
        });
    return total.get();
  }
}
