package dev.vlaisanem.automation.runner.service.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunOutcome;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBackedRunEventJournalTest {

  private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Test
  void assignsAContinuousSequenceStartingAtOne(@TempDir Path tempDir) {
    FileBackedRunEventJournal journal = newJournal(tempDir);

    RunnerEvent first = journal.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW));
    RunnerEvent second = journal.append("run-1", seq -> RunnerEvent.runStarted("run-1", seq, NOW));
    RunnerEvent third =
        journal.append(
            "run-1", seq -> RunnerEvent.runFinished("run-1", seq, NOW, RunOutcome.SUCCEEDED, null));

    assertThat(first.sequence()).isEqualTo(1);
    assertThat(second.sequence()).isEqualTo(2);
    assertThat(third.sequence()).isEqualTo(3);
  }

  @Test
  void readAfterReturnsEmptyForAnUnknownRunId(@TempDir Path tempDir) {
    FileBackedRunEventJournal journal = newJournal(tempDir);

    assertThat(journal.readAfter("never-seen", 0)).isEmpty();
  }

  @Test
  void readAfterZeroReturnsTheFullHistoryInOrder(@TempDir Path tempDir) {
    FileBackedRunEventJournal journal = newJournal(tempDir);
    journal.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW));
    journal.append("run-1", seq -> RunnerEvent.runStarted("run-1", seq, NOW));

    assertThat(journal.readAfter("run-1", 0))
        .extracting(RunnerEvent::sequence)
        .containsExactly(1L, 2L);
  }

  @Test
  void readAfterASequenceReturnsOnlyLaterEvents(@TempDir Path tempDir) {
    FileBackedRunEventJournal journal = newJournal(tempDir);
    journal.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW));
    journal.append("run-1", seq -> RunnerEvent.runStarted("run-1", seq, NOW));

    assertThat(journal.readAfter("run-1", 1)).extracting(RunnerEvent::sequence).containsExactly(2L);
    assertThat(journal.readAfter("run-1", 2)).isEmpty();
  }

  /**
   * Regression test for the review's finding: a run's in-memory entry must survive past its own
   * RUN_FINISHED - replay has to keep working for a run that finished long ago, which is the most
   * common case, not an edge case.
   */
  @Test
  void readAfterStillWorksAfterTheJournalIsTerminal(@TempDir Path tempDir) {
    FileBackedRunEventJournal journal = newJournal(tempDir);
    journal.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW));
    journal.append(
        "run-1", seq -> RunnerEvent.runFinished("run-1", seq, NOW, RunOutcome.SUCCEEDED, null));

    assertThat(journal.readAfter("run-1", 0))
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.RUN_QUEUED, EventType.RUN_FINISHED);
  }

  /**
   * Mirrors runner-listener's identical concurrency regression test for {@code
   * RunnerEventJsonlWriter}: sequence assignment happens inside the same lock as the physical
   * write, so line order on disk must always match sequence order however many threads append
   * concurrently.
   */
  @Test
  void physicalLineOrderMatchesSequenceOrderUnderConcurrentAppend(@TempDir Path tempDir)
      throws Exception {
    FileBackedRunEventJournal journal = newJournal(tempDir);
    int threadCount = 8;
    int appendsPerThread = 50;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch ready = new CountDownLatch(threadCount);
    CountDownLatch start = new CountDownLatch(1);

    for (int t = 0; t < threadCount; t++) {
      executor.submit(
          () -> {
            ready.countDown();
            awaitUninterruptibly(start);
            for (int i = 0; i < appendsPerThread; i++) {
              journal.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW));
            }
          });
    }
    ready.await();
    start.countDown();
    executor.shutdown();
    assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

    List<RunnerEvent> events = readEvents(tempDir.resolve("journal").resolve("run-1.events.jsonl"));
    assertThat(events).hasSize(threadCount * appendsPerThread);
    assertThat(events)
        .extracting(RunnerEvent::sequence)
        .containsExactlyElementsOf(LongStream.rangeClosed(1, events.size()).boxed().toList());

    journal.shutdown(); // never reached RUN_FINISHED - close it explicitly rather than leak the
    // open Writer, which would otherwise hold this test's @TempDir file open on Windows.
  }

  /**
   * Regression test for the review's finding: a misbehaving factory that ignores the sequence
   * number it was handed (returning some other value instead) must be rejected before anything is
   * physically written - otherwise the journal's own counter and the recorded event's sequence
   * could permanently drift apart, breaking the "sole owner of a continuous sequence" guarantee.
   */
  @Test
  void rejectsAnEventWhoseSequenceDoesNotMatchWhatTheJournalAssigned(@TempDir Path tempDir) {
    FileBackedRunEventJournal journal = newJournal(tempDir);

    assertThatThrownBy(
            () -> journal.append("run-1", seq -> RunnerEvent.runQueued("run-1", 99, NOW)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("99")
        .hasMessageContaining("1");

    // The rejected attempt must not have consumed sequence 1.
    RunnerEvent first = journal.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW));
    assertThat(first.sequence()).isEqualTo(1);

    journal.shutdown();
  }

  /**
   * Regression test for the review's finding: {@code CREATE_NEW} on the data file alone does not
   * protect against a stale completion marker left behind without its data file (e.g. a runId
   * reused after manual cleanup that missed the marker) - opening must reject that runId outright,
   * and must not create a fresh data file while doing so.
   */
  @Test
  void refusesToOpenWhenOnlyAStaleCompletionMarkerExists(@TempDir Path tempDir) throws IOException {
    Path journalDir = tempDir.resolve("journal");
    Files.createDirectories(journalDir);
    Files.createFile(journalDir.resolve("run-1.events.complete"));
    FileBackedRunEventJournal journal = newJournal(tempDir);

    assertThatThrownBy(
            () -> journal.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW)))
        .isInstanceOf(RunEventJournalConflictException.class);

    assertThat(Files.exists(journalDir.resolve("run-1.events.jsonl"))).isFalse();
  }

  /**
   * A path/configuration failure while creating the journal directory is genuine I/O trouble, not
   * evidence that this runId's timeline already exists. Only CREATE_NEW collisions on the data file
   * itself belong to the public conflict contract.
   */
  @Test
  void reportsAnInvalidJournalDirectoryAsAnIoFailure(@TempDir Path tempDir) throws IOException {
    Path journalDir = tempDir.resolve("journal");
    Files.writeString(journalDir, "not a directory");
    FileBackedRunEventJournal journal = newJournal(tempDir);

    assertThatThrownBy(
            () -> journal.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW)))
        .isInstanceOf(UncheckedIOException.class)
        .isNotInstanceOf(RunEventJournalConflictException.class)
        .hasMessageContaining("journal directory");
  }

  /**
   * Regression test for the review's finding: a journal that never reaches {@code RUN_FINISHED}
   * (e.g. the service is stopped mid-run) must still be closed - and must not fabricate a
   * completion marker for a run that never actually finished.
   */
  @Test
  void shutdownClosesAnOpenJournalWithoutCreatingTheCompletionMarker(@TempDir Path tempDir) {
    FileBackedRunEventJournal journal = newJournal(tempDir);
    journal.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW));

    journal.shutdown();

    assertThat(Files.exists(tempDir.resolve("journal").resolve("run-1.events.complete"))).isFalse();
  }

  /**
   * Regression test for the review's finding: once shut down, the component must refuse a runId it
   * has never seen before too - not merely one whose journal happened to already exist in memory -
   * and must never create a data file for it.
   */
  @Test
  void rejectsAppendsForABrandNewRunIdAfterShutdown(@TempDir Path tempDir) {
    FileBackedRunEventJournal journal = newJournal(tempDir);
    journal.shutdown();

    assertThatThrownBy(
            () -> journal.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW)))
        .isInstanceOf(RunEventJournalConflictException.class);

    assertThat(Files.exists(tempDir.resolve("journal").resolve("run-1.events.jsonl"))).isFalse();
  }

  /**
   * Regression test for the review's finding: {@code shutdown()} used to iterate a
   * weakly-consistent {@code ConcurrentHashMap} view and then {@code clear()} it, with no
   * coordination against a concurrent {@code append()} - a journal opened (or still mid-append)
   * right around that window could survive shutdown entirely unclosed, or be wiped from the map
   * without ever having its Writer closed. The shared read lock / exclusive write lock pairing must
   * instead make shutdown wait for an in-flight append to fully finish before it can proceed at all
   * - proven here by blocking an append mid-flight and confirming a concurrently started {@code
   * shutdown()} future cannot complete until that append is released.
   */
  @Test
  void shutdownWaitsForAnInFlightAppendToFinishBeforeClosingEverything(@TempDir Path tempDir)
      throws Exception {
    FileBackedRunEventJournal journal = newJournal(tempDir);
    CountDownLatch appendEntered = new CountDownLatch(1);
    CountDownLatch releaseAppend = new CountDownLatch(1);
    CountDownLatch shutdownInvoked = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);

    try {
      Future<?> appendFuture =
          executor.submit(
              () ->
                  journal.append(
                      "run-1",
                      seq -> {
                        appendEntered.countDown();
                        awaitUninterruptibly(releaseAppend);
                        return RunnerEvent.runQueued("run-1", seq, NOW);
                      }));
      assertThat(appendEntered.await(5, TimeUnit.SECONDS)).isTrue();

      Future<?> shutdownFuture =
          executor.submit(
              () -> {
                shutdownInvoked.countDown();
                journal.shutdown();
              });
      assertThat(shutdownInvoked.await(5, TimeUnit.SECONDS)).isTrue();

      // shutdown() cannot complete while append() owns the lifecycle read lock. Future#get also
      // propagates any background exception instead of letting a raw worker thread fail silently.
      assertThatThrownBy(() -> shutdownFuture.get(200, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      releaseAppend.countDown();
      appendFuture.get(5, TimeUnit.SECONDS);
      shutdownFuture.get(5, TimeUnit.SECONDS);

      assertThat(Files.exists(tempDir.resolve("journal").resolve("run-1.events.complete")))
          .isFalse();
    } finally {
      releaseAppend.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  /**
   * Once RUN_FINISHED closes a journal, its in-memory entry is dropped (see {@link
   * FileBackedRunEventJournal#shutdown()}'s Javadoc) - a further append for the same runId
   * therefore re-opens from scratch and is rejected by the on-disk completion marker instead of an
   * in-memory flag, but rejected either way.
   */
  @Test
  void rejectsFurtherAppendsOnceRunFinishedIsRecorded(@TempDir Path tempDir) {
    FileBackedRunEventJournal journal = newJournal(tempDir);
    journal.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW));
    journal.append(
        "run-1", seq -> RunnerEvent.runFinished("run-1", seq, NOW, RunOutcome.SUCCEEDED, null));

    assertThatThrownBy(
            () -> journal.append("run-1", seq -> RunnerEvent.runStarted("run-1", seq, NOW)))
        .isInstanceOf(RunEventJournalConflictException.class);
  }

  @Test
  void rejectsASecondRunFinished(@TempDir Path tempDir) {
    FileBackedRunEventJournal journal = newJournal(tempDir);
    journal.append(
        "run-1", seq -> RunnerEvent.runFinished("run-1", seq, NOW, RunOutcome.SUCCEEDED, null));

    assertThatThrownBy(
            () ->
                journal.append(
                    "run-1",
                    seq -> RunnerEvent.runFinished("run-1", seq, NOW, RunOutcome.FAILED, null)))
        .isInstanceOf(RunEventJournalConflictException.class);
  }

  @Test
  void createsTheCompletionMarkerOnlyAfterRunFinishedCloses(@TempDir Path tempDir) {
    FileBackedRunEventJournal journal = newJournal(tempDir);
    Path marker = tempDir.resolve("journal").resolve("run-1.events.complete");

    journal.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW));
    assertThat(Files.exists(marker)).isFalse();

    journal.append(
        "run-1", seq -> RunnerEvent.runFinished("run-1", seq, NOW, RunOutcome.SUCCEEDED, null));
    assertThat(Files.exists(marker)).isTrue();
  }

  @Test
  void reopeningTheSameRunIdFailsLoudlyInsteadOfSilentlyReusingTheFile(@TempDir Path tempDir) {
    FileBackedRunEventJournal firstInstance = newJournal(tempDir);
    firstInstance.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW));

    FileBackedRunEventJournal secondInstance = newJournal(tempDir);

    assertThatThrownBy(
            () -> secondInstance.append("run-1", seq -> RunnerEvent.runStarted("run-1", seq, NOW)))
        .isInstanceOf(RunEventJournalConflictException.class);

    firstInstance.shutdown();
  }

  @Test
  void rejectsAnEventWhoseRunIdDoesNotMatchTheJournalItWasAppendedTo(@TempDir Path tempDir) {
    FileBackedRunEventJournal journal = newJournal(tempDir);

    assertThatThrownBy(
            () -> journal.append("run-1", seq -> RunnerEvent.runQueued("run-2", seq, NOW)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("run-2");

    journal.shutdown();
  }

  /**
   * Regression coverage for the plan's "write failure ne ostavlja lažni event" requirement: a write
   * failure must not silently succeed, must not leave the journal usable afterward (a physically
   * incomplete stream can never become trustworthy again), and must never produce a completion
   * marker. The poisoned entry is dropped from memory (see {@link
   * FileBackedRunEventJournal#shutdown()}'s Javadoc), so a further attempt re-opens from scratch
   * and is rejected as a {@link RunEventJournalConflictException} by the now-existing (empty) data
   * file - the same public contract {@link RunEventAppender#append} promises regardless.
   */
  @Test
  void writeFailurePermanentlyPoisonsTheJournalAndSkipsTheMarker(@TempDir Path tempDir)
      throws JsonProcessingException {
    ObjectMapper failingMapper = mock(ObjectMapper.class);
    when(failingMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
    FileBackedRunEventJournal journal =
        new FileBackedRunEventJournal(propertiesWithJournalDir(tempDir), failingMapper);

    // The write failure itself is a genuine unexpected I/O failure, not a conflict.
    assertThatThrownBy(
            () -> journal.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW)))
        .isInstanceOf(UncheckedIOException.class);

    assertThatThrownBy(
            () -> journal.append("run-1", seq -> RunnerEvent.runStarted("run-1", seq, NOW)))
        .isInstanceOf(RunEventJournalConflictException.class);

    assertThat(Files.exists(tempDir.resolve("journal").resolve("run-1.events.complete"))).isFalse();
  }

  private FileBackedRunEventJournal newJournal(Path tempDir) {
    return new FileBackedRunEventJournal(propertiesWithJournalDir(tempDir), OBJECT_MAPPER);
  }

  private RunnerProperties propertiesWithJournalDir(Path tempDir) {
    return new RunnerProperties(
        ".",
        Duration.ofSeconds(30),
        tempDir.resolve("raw").toString(),
        tempDir.resolve("journal").toString(),
        tempDir.resolve("logs").toString(),
        "src/test/resources/catalog/public-test-catalog.json",
        tempDir.resolve("artifacts").toString(),
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
