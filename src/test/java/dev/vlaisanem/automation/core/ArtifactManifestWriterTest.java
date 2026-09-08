package dev.vlaisanem.automation.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.contract.ArtifactType;
import dev.vlaisanem.automation.support.JsonSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactManifestWriterTest {

  @Test
  void appendsAnEntryThatRoundTripsWithTheActualFileSize(@TempDir Path artifactsRoot)
      throws IOException {
    Path artifact = artifactsRoot.resolve("tests").resolve("some-test").resolve("failure.png");
    Files.createDirectories(artifact.getParent());
    Files.writeString(artifact, "not really a png, just needs a real size");

    ArtifactManifestWriter.record(
        artifactsRoot,
        "run-1",
        "test-id-1",
        "someTest()",
        null,
        ArtifactType.SCREENSHOT,
        artifact,
        "image/png",
        26_214_400L,
        209_715_200L,
        2_097_152L);

    List<ArtifactManifestEntry> entries = readManifest(artifactsRoot);
    assertThat(entries).hasSize(1);
    ArtifactManifestEntry entry = entries.get(0);
    assertThat(entry.runId()).isEqualTo("run-1");
    assertThat(entry.testId()).isEqualTo("test-id-1");
    assertThat(entry.testDisplayName()).isEqualTo("someTest()");
    assertThat(entry.type()).isEqualTo(ArtifactType.SCREENSHOT);
    assertThat(entry.mediaType()).isEqualTo("image/png");
    assertThat(entry.sizeBytes()).isEqualTo(Files.size(artifact));
    // Always '/' - verified on this very machine (Windows), where Path.relativize() alone would
    // have produced backslashes and silently violated ArtifactManifestEntry's own contract.
    assertThat(entry.relativePath()).isEqualTo("tests/some-test/failure.png");
  }

  @Test
  void appendingTwiceKeepsBothEntriesRatherThanOverwriting(@TempDir Path artifactsRoot)
      throws IOException {
    Path first = artifactsRoot.resolve("failure.png");
    Path second = artifactsRoot.resolve("trace.zip");
    Files.writeString(first, "a");
    Files.writeString(second, "bb");

    ArtifactManifestWriter.record(
        artifactsRoot,
        "run-1",
        "test-1",
        "test one",
        null,
        ArtifactType.SCREENSHOT,
        first,
        "image/png",
        26_214_400L,
        209_715_200L,
        2_097_152L);
    ArtifactManifestWriter.record(
        artifactsRoot,
        "run-1",
        "test-1",
        "test one",
        null,
        ArtifactType.TRACE,
        second,
        "application/zip",
        26_214_400L,
        209_715_200L,
        2_097_152L);

    List<ArtifactManifestEntry> entries = readManifest(artifactsRoot);
    assertThat(entries)
        .extracting(ArtifactManifestEntry::type)
        .containsExactly(ArtifactType.SCREENSHOT, ArtifactType.TRACE);
  }

  /**
   * Proves {@link ArtifactManifestWriter}'s own explicit locking (in-JVM {@code synchronized} plus
   * an OS-level {@link java.nio.channels.FileLock}) actually serializes concurrent writers, rather
   * than relying on any accidental platform-specific atomicity: every one of many
   * concurrently-appended lines must still be a complete, independently-parseable JSON object - not
   * a single call actually interleaved with another mid-line.
   *
   * <p><b>Known gap, deliberately not closed here:</b> this only exercises the in-JVM {@code
   * synchronized} layer - a single in-process JVM cannot prove the {@link
   * java.nio.channels.FileLock} actually protects a <em>second, separate</em> JVM process from
   * interleaving with this one (that would need a small standalone fixture launched as a real child
   * process, mirroring {@code GradleProcessRunnerTest}'s own fixtures). Today's A1 guarantee (one
   * test JVM per run's own artifacts directory) makes that scenario unreachable in production, so
   * this is tracked as backlog hardening rather than blocking A2.
   */
  @Test
  void concurrentAppendsNeverInterleaveOrLoseAnEntry(@TempDir Path artifactsRoot) throws Exception {
    int writers = 20;
    Path artifact = artifactsRoot.resolve("failure.png");
    Files.writeString(artifact, "x");
    // One thread per writer, not a smaller fixed pool: every task blocks on `release` after its own
    // `ready.countDown()`, so a pool smaller than `writers` would deadlock - every thread would be
    // stuck waiting on `release` while `ready` can never reach zero without more threads than the
    // pool has to give.
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    try {
      CountDownLatch ready = new CountDownLatch(writers);
      CountDownLatch release = new CountDownLatch(1);
      List<Future<?>> tasks =
          IntStream.range(0, writers)
              .mapToObj(
                  i ->
                      pool.submit(
                          () -> {
                            ready.countDown();
                            release.await();
                            ArtifactManifestWriter.record(
                                artifactsRoot,
                                "run-1",
                                "test-" + i,
                                "test " + i,
                                null,
                                ArtifactType.SCREENSHOT,
                                artifact,
                                "image/png",
                                26_214_400L,
                                209_715_200L,
                                2_097_152L);
                            return null;
                          }))
              .collect(Collectors.toList());
      ready.await();
      release.countDown();
      for (Future<?> task : tasks) {
        task.get();
      }

      List<ArtifactManifestEntry> entries = readManifest(artifactsRoot);
      assertThat(entries).hasSize(writers);
      assertThat(entries.stream().map(ArtifactManifestEntry::testId).distinct().count())
          .isEqualTo(writers);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void rejectsAndDeletesAnOversizedArtifact(@TempDir Path artifactsRoot) throws IOException {
    Path artifact = artifactsRoot.resolve("failure.png");
    Files.writeString(artifact, "this is definitely more than eight bytes");

    boolean recorded =
        ArtifactManifestWriter.record(
            artifactsRoot,
            "run-1",
            "test-1",
            "test one",
            null,
            ArtifactType.SCREENSHOT,
            artifact,
            "image/png",
            8L,
            209_715_200L,
            2_097_152L);

    assertThat(recorded).isFalse();
    assertThat(artifact).doesNotExist();
    assertThat(readManifest(artifactsRoot)).isEmpty();
  }

  @Test
  void refusesToGrowTheManifestPastItsConfiguredLimitAndDeletesTheArtifact(
      @TempDir Path artifactsRoot) throws IOException {
    Path artifact = artifactsRoot.resolve("failure.png");
    Files.writeString(artifact, "x");

    boolean recorded =
        ArtifactManifestWriter.record(
            artifactsRoot,
            "run-1",
            "test-1",
            "test one",
            null,
            ArtifactType.SCREENSHOT,
            artifact,
            "image/png",
            26_214_400L,
            209_715_200L,
            // A single manifest line is well over 1 byte, so this always rejects.
            1L);

    assertThat(recorded).isFalse();
    assertThat(artifact).doesNotExist();
    assertThat(readManifest(artifactsRoot)).isEmpty();
  }

  @Test
  void rejectsAndDeletesAnArtifactThatWouldExceedTheRunTotalBudget(@TempDir Path artifactsRoot)
      throws IOException {
    Path first = artifactsRoot.resolve("first.png");
    Path second = artifactsRoot.resolve("second.png");
    Files.write(first, new byte[10]);

    boolean firstRecorded =
        ArtifactManifestWriter.record(
            artifactsRoot,
            "run-1",
            "test-1",
            "test one",
            null,
            ArtifactType.SCREENSHOT,
            first,
            "image/png",
            26_214_400L,
            15L,
            2_097_152L);
    // Written only now, mirroring real usage: the next artifact's own file doesn't exist on disk
    // until its own capture step runs, immediately before its own record() call.
    Files.write(second, new byte[10]);
    boolean secondRecorded =
        ArtifactManifestWriter.record(
            artifactsRoot,
            "run-1",
            "test-2",
            "test two",
            null,
            ArtifactType.SCREENSHOT,
            second,
            "image/png",
            26_214_400L,
            15L,
            2_097_152L);

    assertThat(firstRecorded).isTrue();
    assertThat(secondRecorded).isFalse();
    assertThat(first).exists();
    assertThat(second).doesNotExist();
    List<ArtifactManifestEntry> entries = readManifest(artifactsRoot);
    assertThat(entries).extracting(ArtifactManifestEntry::testId).containsExactly("test-1");
  }

  private static List<ArtifactManifestEntry> readManifest(Path artifactsRoot) throws IOException {
    Path manifest = artifactsRoot.resolve("manifest.jsonl");
    if (!Files.exists(manifest)) {
      return List.of();
    }
    return Files.readAllLines(manifest).stream()
        .map(line -> JsonSupport.read(line, ArtifactManifestEntry.class))
        .toList();
  }
}
