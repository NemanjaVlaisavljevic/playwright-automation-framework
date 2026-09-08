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
 * Appends one {@link ArtifactManifestEntry} JSON Line per artifact to {@code manifest.jsonl} inside
 * that run's own artifacts root (see {@code TestConfig#artifactsDirectory()}, already run-scoped as
 * of the runner-service's {@code ARTIFACTS_DIR}).
 *
 * <p>Two layers of locking, not one - a small {@code APPEND}-mode write is NOT a portable atomicity
 * guarantee across every OS/filesystem (unlike a single process's own {@code O_APPEND} behavior on
 * a given local filesystem, nothing in the Java NIO API promises this holds everywhere, and this
 * project already runs test classes concurrently within one JVM - see junit-platform.properties):
 *
 * <ul>
 *   <li>an in-JVM {@code synchronized} lock, keyed by the manifest file's own absolute path, so two
 *       threads in the same JVM (the common case here) never race at all - a {@link FileLock}
 *       acquired by a second thread of the <em>same</em> JVM would throw {@code
 *       OverlappingFileLockException} rather than block, so this must be handled before ever
 *       reaching the file lock below, not instead of it.
 *   <li>an OS-level {@link FileLock} on the channel, acquired before every write - this is what
 *       actually protects two separate JVM processes (e.g. two independent Gradle invocations
 *       somehow targeting the same manifest file) from interleaving, which no in-JVM lock could
 *       ever reach.
 * </ul>
 *
 * <p>The file's current size is read only after the {@link FileLock} is held, never before -
 * reading it earlier could observe a stale end-of-file position if another writer's append landed
 * in between, causing this write to silently overwrite (rather than follow) it.
 */
final class ArtifactManifestWriter {

  private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactManifestWriter.class);
  private static final ConcurrentHashMap<Path, Object> LOCKS_BY_MANIFEST_PATH =
      new ConcurrentHashMap<>();

  private ArtifactManifestWriter() {}

  /**
   * Builds the entry (a fresh opaque {@code artifactId}, the file's actual size once it is fully
   * written, {@code relativePath} normalized to forward slashes regardless of platform) and appends
   * it - unless {@code artifactFile} itself already exceeds {@code artifactMaxBytes} (a second,
   * independent layer of defense in depth behind whatever pre-write cap the caller may already have
   * applied - the only layer at all for a capture API, like a Playwright trace, with no in-memory
   * alternative), or the manifest/per-run-total D4.2 budgets reject it (see {@link #append}) - in
   * either case {@code artifactFile} is deleted and this returns {@code false} rather than leaving
   * a disk-consuming file with no manifest reference at all. Callers are expected to catch {@link
   * IOException} the same way they already treat any other best-effort artifact-capture failure -
   * this never throws anything artifact capture itself did not already risk throwing.
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
   * @return {@code true} if the line was actually appended; {@code false} if either D4.2 budget
   *     (the manifest's own size, or the run's real total artifact-directory size, computed fresh
   *     under this same lock rather than an in-memory counter - a counter would not hold across the
   *     two separate OS processes this method already supports, see this class's own Javadoc)
   *     rejected it.
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
        // The candidate artifact file this entry describes was already written to disk before
        // record() was ever called, so this walk already includes it - no separate "plus the new
        // file's own size" addition is needed.
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
