package dev.vlaisanem.automation.core;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.contract.ArtifactType;
import dev.vlaisanem.automation.support.JsonSupport;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

  private static final ConcurrentHashMap<Path, Object> LOCKS_BY_MANIFEST_PATH =
      new ConcurrentHashMap<>();

  private ArtifactManifestWriter() {}

  /**
   * Builds the entry (a fresh opaque {@code artifactId}, the file's actual size once it is fully
   * written, {@code relativePath} normalized to forward slashes regardless of platform) and appends
   * it. Callers are expected to catch {@link IOException} the same way they already treat any other
   * best-effort artifact-capture failure - this never throws anything artifact capture itself did
   * not already risk throwing.
   */
  static void record(
      Path artifactsRoot,
      String runId,
      String testId,
      String testDisplayName,
      ArtifactType type,
      Path artifactFile,
      String mediaType)
      throws IOException {
    String relativePath = artifactsRoot.relativize(artifactFile).toString().replace('\\', '/');
    ArtifactManifestEntry entry =
        new ArtifactManifestEntry(
            ArtifactManifestEntry.CURRENT_SCHEMA_VERSION,
            UUID.randomUUID().toString(),
            runId,
            testId,
            testDisplayName,
            type,
            relativePath,
            mediaType,
            Files.size(artifactFile),
            Instant.now());
    append(artifactsRoot, entry);
  }

  private static void append(Path artifactsRoot, ArtifactManifestEntry entry) throws IOException {
    Path manifestFile = artifactsRoot.resolve("manifest.jsonl").toAbsolutePath().normalize();
    byte[] line = (JsonSupport.write(entry) + "\n").getBytes(StandardCharsets.UTF_8);
    Object inProcessLock =
        LOCKS_BY_MANIFEST_PATH.computeIfAbsent(manifestFile, unused -> new Object());
    synchronized (inProcessLock) {
      Files.createDirectories(manifestFile.getParent());
      try (FileChannel channel =
              FileChannel.open(manifestFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          FileLock fileLock = channel.lock()) {
        channel.position(channel.size());
        ByteBuffer buffer = ByteBuffer.wrap(line);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
      }
    }
  }
}
