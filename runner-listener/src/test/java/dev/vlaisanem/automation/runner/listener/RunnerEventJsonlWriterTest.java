package dev.vlaisanem.automation.runner.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunnerEventJsonlWriterTest {

  private static final ObjectMapper OBJECT_MAPPER = RunnerEventObjectMapper.create();

  /**
   * Line order on disk must always match sequence order, however many threads write concurrently.
   */
  @Test
  void assignsSequenceNumbersInTheSameOrderTheyLandOnDiskUnderConcurrency(@TempDir Path tempDir)
      throws Exception {
    Path file = tempDir.resolve("concurrent.jsonl");
    RunnerEventJsonlWriter writer =
        new RunnerEventJsonlWriter(
            file,
            tempDir.resolve("concurrent.complete"),
            tempDir.resolve("concurrent.overflow"),
            1_000_000L,
            OBJECT_MAPPER);

    int threadCount = 8;
    int writesPerThread = 50;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch start = new CountDownLatch(1);

    for (int t = 0; t < threadCount; t++) {
      executor.submit(
          () -> {
            ready.countDown();
            awaitUninterruptibly(start);
            for (int i = 0; i < writesPerThread; i++) {
              writer.write(
                  seq -> RunnerEvent.testStarted("run-1", seq, Instant.now(), "test-id", "name"));
            }
          });
    }
    ready.await();
    start.countDown();
    executor.shutdown();
    assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    writer.close();

    List<RunnerEvent> events = readEvents(file);
    assertThat(events).hasSize(threadCount * writesPerThread);
    assertThat(events)
        .extracting(RunnerEvent::sequence)
        .containsExactlyElementsOf(LongStream.rangeClosed(1, events.size()).boxed().toList());
  }

  @Test
  void closeCreatesACompletionMarkerOnlyAfterClosing(@TempDir Path tempDir) {
    Path file = tempDir.resolve("run.jsonl");
    Path marker = tempDir.resolve("run.complete");
    RunnerEventJsonlWriter writer =
        new RunnerEventJsonlWriter(
            file, marker, tempDir.resolve("run.overflow"), 1_000_000L, OBJECT_MAPPER);

    assertThat(Files.exists(marker)).isFalse();
    writer.close();
    assertThat(Files.exists(marker)).isTrue();
  }

  /**
   * JUnit Platform only logs a listener exception, never fails the run - without this, a failed
   * write would leave a truncated JSONL file with a marker falsely claiming completeness.
   */
  @Test
  void closeDoesNotCreateAMarkerAfterAFailedWrite(@TempDir Path tempDir) {
    Path file = tempDir.resolve("run.jsonl");
    Path marker = tempDir.resolve("run.complete");
    RunnerEventJsonlWriter writer =
        new RunnerEventJsonlWriter(
            file, marker, tempDir.resolve("run.overflow"), 1_000_000L, OBJECT_MAPPER);

    assertThatThrownBy(
            () ->
                writer.write(
                    seq -> {
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("boom");

    writer.close();

    assertThat(Files.exists(marker)).isFalse();
  }

  /**
   * {@code CREATE_NEW} on the data file alone does not protect against a stale completion marker
   * left behind without its data file - opening must reject the runId and create nothing.
   */
  @Test
  void refusesToOpenWhenOnlyAStaleCompletionMarkerExists(@TempDir Path tempDir) throws IOException {
    Path file = tempDir.resolve("run.jsonl");
    Path marker = tempDir.resolve("run.complete");
    Files.createFile(marker);

    assertThatThrownBy(
            () ->
                new RunnerEventJsonlWriter(
                    file, marker, tempDir.resolve("run.overflow"), 1_000_000L, OBJECT_MAPPER))
        .isInstanceOf(UncheckedIOException.class);

    assertThat(Files.exists(file)).isFalse();
  }

  /**
   * Once the byte cap is hit, further events are dropped and {@code close()} must create the
   * overflow marker instead of the completion marker, so a consumer never mistakes a truncated
   * stream for a complete one.
   */
  @Test
  void createsAnOverflowMarkerInsteadOfACompletionMarkerOnceTheByteCapIsHit(@TempDir Path tempDir) {
    Path file = tempDir.resolve("run.jsonl");
    Path marker = tempDir.resolve("run.complete");
    Path overflowMarker = tempDir.resolve("run.overflow");
    // Small enough that even the very first event breaches it.
    RunnerEventJsonlWriter writer =
        new RunnerEventJsonlWriter(file, marker, overflowMarker, 1L, OBJECT_MAPPER);

    writer.write(seq -> RunnerEvent.testStarted("run-1", seq, Instant.now(), "test-id", "name"));
    writer.write(seq -> RunnerEvent.testStarted("run-1", seq, Instant.now(), "test-id", "name"));
    writer.close();

    assertThat(Files.exists(marker)).isFalse();
    assertThat(Files.exists(overflowMarker)).isTrue();
  }

  @Test
  void refusesToOpenWhenOnlyAStaleOverflowMarkerExists(@TempDir Path tempDir) throws IOException {
    Path file = tempDir.resolve("run.jsonl");
    Path marker = tempDir.resolve("run.complete");
    Path overflowMarker = tempDir.resolve("run.overflow");
    Files.createFile(overflowMarker);

    assertThatThrownBy(
            () ->
                new RunnerEventJsonlWriter(file, marker, overflowMarker, 1_000_000L, OBJECT_MAPPER))
        .isInstanceOf(UncheckedIOException.class);

    assertThat(Files.exists(file)).isFalse();
  }

  private void awaitUninterruptibly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  private List<RunnerEvent> readEvents(Path file) throws IOException {
    return Files.readAllLines(file).stream()
        .map(
            line -> {
              try {
                return OBJECT_MAPPER.readValue(line, RunnerEvent.class);
              } catch (IOException exception) {
                throw new UncheckedIOException(exception);
              }
            })
        .toList();
  }
}
