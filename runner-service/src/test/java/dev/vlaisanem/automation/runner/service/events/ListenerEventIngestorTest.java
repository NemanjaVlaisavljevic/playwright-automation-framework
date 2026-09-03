package dev.vlaisanem.automation.runner.service.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ListenerEventIngestorTest {

  private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private static final Duration SHORT_POLL = Duration.ofMillis(20);
  private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(5);

  @Test
  void forwardsEveryValidLineWithAFreshCanonicalSequence(@TempDir Path dir) throws IOException {
    String runId = "run-1";
    Path dataFile = dir.resolve(runId + ".tests.jsonl");
    Path marker = dir.resolve(runId + ".tests.complete");
    writeLines(
        dataFile,
        RunnerEvent.testStarted(runId, 1, NOW, "t1", "test one"),
        RunnerEvent.testPassed(runId, 2, NOW, "t1", "test one"));
    Files.createFile(marker);
    RecordingRunEventAppender appender = new RecordingRunEventAppender();

    ListenerEventIngestor ingestor = newIngestor(runId, dataFile, marker, appender);
    IngestionResult result = ingestor.stopAndAwaitFinished(DRAIN_TIMEOUT);

    assertThat(result.valid()).isTrue();
    assertThat(result.sawCompletionMarker()).isTrue();
    List<RunnerEvent> forwarded = appender.eventsFor(runId);
    assertThat(forwarded)
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.TEST_STARTED, EventType.TEST_PASSED);
    // Canonical sequence, assigned by the appender - deliberately independent of the raw source
    // sequence the listener itself assigned (also 1 and 2 here, but never guaranteed to match).
    assertThat(forwarded).extracting(RunnerEvent::sequence).containsExactly(1L, 2L);
  }

  /**
   * Proves event-vocabulary coexistence at the ingestion boundary (Faza B): every event below
   * carries the same {@code schemaVersion} - a run mixing an ordinary test (no steps) with one that
   * used the {@code Steps} API (interleaved {@code STEP_*} events) must ingest both patterns side
   * by side under one strictly monotonic canonical sequence - see {@code EventType}'s own Javadoc
   * on why {@code STEP_*} is additive, not a breaking change.
   */
  @Test
  void forwardsInterleavedStepAndTestLevelEventsFromTheSameRun(@TempDir Path dir)
      throws IOException {
    String runId = "run-1";
    Path dataFile = dir.resolve(runId + ".tests.jsonl");
    Path marker = dir.resolve(runId + ".tests.complete");
    writeLines(
        dataFile,
        RunnerEvent.testStarted(runId, 1, NOW, "t1", "test one"),
        RunnerEvent.testPassed(runId, 2, NOW, "t1", "test one"),
        RunnerEvent.testStarted(runId, 3, NOW, "t2", "test two"),
        RunnerEvent.stepStarted(runId, 4, NOW, "t2", "test two", "s1", "step one"),
        RunnerEvent.stepPassed(runId, 5, NOW, "t2", "test two", "s1", "step one"),
        RunnerEvent.stepStarted(runId, 6, NOW, "t2", "test two", "s2", "step two"),
        RunnerEvent.stepFailed(runId, 7, NOW, "t2", "test two", "s2", "step two", "boom"),
        RunnerEvent.testFailed(runId, 8, NOW, "t2", "test two", "boom"));
    Files.createFile(marker);
    RecordingRunEventAppender appender = new RecordingRunEventAppender();

    ListenerEventIngestor ingestor = newIngestor(runId, dataFile, marker, appender);
    IngestionResult result = ingestor.stopAndAwaitFinished(DRAIN_TIMEOUT);

    assertThat(result.valid()).isTrue();
    List<RunnerEvent> forwarded = appender.eventsFor(runId);
    assertThat(forwarded)
        .extracting(RunnerEvent::type)
        .containsExactly(
            EventType.TEST_STARTED,
            EventType.TEST_PASSED,
            EventType.TEST_STARTED,
            EventType.STEP_STARTED,
            EventType.STEP_PASSED,
            EventType.STEP_STARTED,
            EventType.STEP_FAILED,
            EventType.TEST_FAILED);
    assertThat(forwarded)
        .extracting(RunnerEvent::sequence)
        .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
  }

  /**
   * Regression test for the review's finding: the ingestor must poll for new bytes rather than read
   * the file only once, since WatchService-style notification can coalesce rapid writes on Windows.
   */
  @Test
  void picksUpLinesWrittenIncrementallyWhilePolling(@TempDir Path dir) throws Exception {
    String runId = "run-1";
    Path dataFile = dir.resolve(runId + ".tests.jsonl");
    Path marker = dir.resolve(runId + ".tests.complete");
    RecordingRunEventAppender appender = new RecordingRunEventAppender();
    ListenerEventIngestor ingestor = newIngestor(runId, dataFile, marker, appender);

    writeLines(dataFile, RunnerEvent.testStarted(runId, 1, NOW, "t1", "test one"));
    awaitEventCount(appender, runId, 1);

    appendLine(dataFile, RunnerEvent.testPassed(runId, 2, NOW, "t1", "test one"));
    awaitEventCount(appender, runId, 2);

    Files.createFile(marker);
    IngestionResult result = ingestor.stopAndAwaitFinished(DRAIN_TIMEOUT);

    assertThat(result.valid()).isTrue();
    assertThat(result.sawCompletionMarker()).isTrue();
    assertThat(appender.eventsFor(runId)).hasSize(2);
  }

  /**
   * Regression test for the agreed ingestion-outcome rule: a trailing fragment left behind by a
   * force-kill must be discarded, not treated as a validation failure, when ingestion is stopped
   * (rather than completed naturally via the marker) - {@link RunService} alone decides whether the
   * resulting missing marker is tolerable for CANCELLED/TIMED_OUT.
   */
  @Test
  void toleratesATrailingIncompleteLineWhenStoppedWithoutAMarker(@TempDir Path dir)
      throws IOException {
    String runId = "run-1";
    Path dataFile = dir.resolve(runId + ".tests.jsonl");
    Path marker = dir.resolve(runId + ".tests.complete");
    String completeLine =
        OBJECT_MAPPER.writeValueAsString(RunnerEvent.testStarted(runId, 1, NOW, "t1", "test one"));
    Files.writeString(dataFile, completeLine + "\n{\"trunc", StandardCharsets.UTF_8);
    RecordingRunEventAppender appender = new RecordingRunEventAppender();

    ListenerEventIngestor ingestor = newIngestor(runId, dataFile, marker, appender);
    IngestionResult result = ingestor.stopAndAwaitFinished(DRAIN_TIMEOUT);

    assertThat(result.valid()).isTrue();
    assertThat(result.sawCompletionMarker()).isFalse();
    assertThat(appender.eventsFor(runId))
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.TEST_STARTED);
  }

  @Test
  void rejectsAGapInSourceSequence(@TempDir Path dir) throws IOException {
    String runId = "run-1";
    Path dataFile = dir.resolve(runId + ".tests.jsonl");
    Path marker = dir.resolve(runId + ".tests.complete");
    writeLines(
        dataFile,
        RunnerEvent.testStarted(runId, 1, NOW, "t1", "test one"),
        RunnerEvent.testPassed(runId, 3, NOW, "t1", "test one"));
    RecordingRunEventAppender appender = new RecordingRunEventAppender();

    ListenerEventIngestor ingestor = newIngestor(runId, dataFile, marker, appender);
    IngestionResult result = ingestor.stopAndAwaitFinished(DRAIN_TIMEOUT);

    assertThat(result.valid()).isFalse();
    assertThat(result.detail()).contains("gap in source sequence");
  }

  @Test
  void rejectsADuplicateSourceSequence(@TempDir Path dir) throws IOException {
    String runId = "run-1";
    Path dataFile = dir.resolve(runId + ".tests.jsonl");
    Path marker = dir.resolve(runId + ".tests.complete");
    writeLines(
        dataFile,
        RunnerEvent.testStarted(runId, 1, NOW, "t1", "test one"),
        RunnerEvent.testPassed(runId, 1, NOW, "t1", "test one"));
    RecordingRunEventAppender appender = new RecordingRunEventAppender();

    ListenerEventIngestor ingestor = newIngestor(runId, dataFile, marker, appender);
    IngestionResult result = ingestor.stopAndAwaitFinished(DRAIN_TIMEOUT);

    assertThat(result.valid()).isFalse();
    assertThat(result.detail()).contains("duplicate source sequence");
  }

  @Test
  void rejectsAnEventForTheWrongRunId(@TempDir Path dir) throws IOException {
    String runId = "run-1";
    Path dataFile = dir.resolve(runId + ".tests.jsonl");
    Path marker = dir.resolve(runId + ".tests.complete");
    writeLines(dataFile, RunnerEvent.testStarted("other-run", 1, NOW, "t1", "test one"));
    RecordingRunEventAppender appender = new RecordingRunEventAppender();

    ListenerEventIngestor ingestor = newIngestor(runId, dataFile, marker, appender);
    IngestionResult result = ingestor.stopAndAwaitFinished(DRAIN_TIMEOUT);

    assertThat(result.valid()).isFalse();
    assertThat(result.detail()).contains("runId mismatch");
  }

  @Test
  void rejectsANonTestLevelEventType(@TempDir Path dir) throws IOException {
    String runId = "run-1";
    Path dataFile = dir.resolve(runId + ".tests.jsonl");
    Path marker = dir.resolve(runId + ".tests.complete");
    writeLines(dataFile, RunnerEvent.runStarted(runId, 1, NOW));
    RecordingRunEventAppender appender = new RecordingRunEventAppender();

    ListenerEventIngestor ingestor = newIngestor(runId, dataFile, marker, appender);
    IngestionResult result = ingestor.stopAndAwaitFinished(DRAIN_TIMEOUT);

    assertThat(result.valid()).isFalse();
    assertThat(result.detail()).contains("non-test-level");
  }

  @Test
  void rejectsMalformedJson(@TempDir Path dir) throws IOException {
    String runId = "run-1";
    Path dataFile = dir.resolve(runId + ".tests.jsonl");
    Path marker = dir.resolve(runId + ".tests.complete");
    Files.writeString(dataFile, "not valid json\n", StandardCharsets.UTF_8);
    RecordingRunEventAppender appender = new RecordingRunEventAppender();

    ListenerEventIngestor ingestor = newIngestor(runId, dataFile, marker, appender);
    IngestionResult result = ingestor.stopAndAwaitFinished(DRAIN_TIMEOUT);

    assertThat(result.valid()).isFalse();
    assertThat(result.detail()).contains("malformed JSON");
  }

  /**
   * Regression test for the review's finding: {@code new String(bytes, UTF_8)} silently replaces an
   * invalid byte sequence with U+FFFD instead of failing - invalid UTF-8 is stream corruption and
   * must be rejected, not papered over into JSON that still happens to parse.
   */
  @Test
  void rejectsInvalidUtf8ByteSequences(@TempDir Path dir) throws IOException {
    String runId = "run-1";
    Path dataFile = dir.resolve(runId + ".tests.jsonl");
    Path marker = dir.resolve(runId + ".tests.complete");
    String prefix =
        "{\"schemaVersion\":\"1.0\",\"runId\":\"run-1\",\"sequence\":1,\"timestamp\":\"2026-08-31T12:00:00Z\",\"type\":\"TEST_STARTED\",\"testId\":\"t1\",\"testDisplayName\":\"";
    byte[] invalidContinuationByte = {(byte) 0x80};
    byte[] line =
        concatBytes(
            prefix.getBytes(StandardCharsets.UTF_8),
            invalidContinuationByte,
            "\"}\n".getBytes(StandardCharsets.UTF_8));
    Files.write(dataFile, line);
    RecordingRunEventAppender appender = new RecordingRunEventAppender();

    ListenerEventIngestor ingestor = newIngestor(runId, dataFile, marker, appender);
    IngestionResult result = ingestor.stopAndAwaitFinished(DRAIN_TIMEOUT);

    assertThat(result.valid()).isFalse();
    assertThat(result.detail()).contains("invalid UTF-8");
    assertThat(appender.eventsFor(runId)).isEmpty();
  }

  /**
   * Regression test for the review's finding: two concurrent {@code stopAndAwaitFinished} calls -
   * one of which cancels the underlying task on timeout - must never let a {@code
   * CancellationException} escape uncaught, and must converge on the same terminal result rather
   * than each independently reporting something different.
   */
  @Test
  void concurrentStopAndAwaitFinishedCallsNeverThrowAndConvergeOnTheSameResult(@TempDir Path dir)
      throws Exception {
    String runId = "run-1";
    Path dataFile = dir.resolve(runId + ".tests.jsonl");
    Path marker = dir.resolve(runId + ".tests.complete");
    RecordingRunEventAppender appender = new RecordingRunEventAppender();
    ListenerEventIngestor ingestor =
        new ListenerEventIngestor(
            runId, dataFile, marker, appender, OBJECT_MAPPER, Duration.ofSeconds(10));
    // Let the loop reach and enter its long poll wait before both callers race to stop it.
    Thread.sleep(200);

    ExecutorService callers = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<IngestionResult>> futures = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      futures.add(
          callers.submit(
              () -> {
                ready.countDown();
                awaitUninterruptibly(start);
                return ingestor.stopAndAwaitFinished(Duration.ofMillis(300));
              }));
    }
    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
    start.countDown();

    IngestionResult first = futures.get(0).get(10, TimeUnit.SECONDS);
    IngestionResult second = futures.get(1).get(10, TimeUnit.SECONDS);
    callers.shutdown();
    assertThat(callers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

    assertThat(first.valid()).isFalse();
    assertThat(second).isEqualTo(first);
  }

  /**
   * Regression test for the review's finding: the real writer always creates the data file before
   * anything else and only creates the marker after closing cleanly, so a marker with no data file
   * at all can only mean corruption (a deleted file, a bad deploy) - it must not be silently
   * treated as "zero test events".
   */
  @Test
  void rejectsAnOrphanMarkerWithNoDataFileAtAll(@TempDir Path dir) throws IOException {
    String runId = "run-1";
    Path dataFile = dir.resolve(runId + ".tests.jsonl");
    Path marker = dir.resolve(runId + ".tests.complete");
    Files.createFile(marker);
    RecordingRunEventAppender appender = new RecordingRunEventAppender();

    ListenerEventIngestor ingestor = newIngestor(runId, dataFile, marker, appender);
    IngestionResult result = ingestor.stopAndAwaitFinished(DRAIN_TIMEOUT);

    assertThat(result.valid()).isFalse();
    assertThat(result.detail()).contains("raw data file was never created");
    assertThat(appender.eventsFor(runId)).isEmpty();
  }

  /**
   * The legitimate "zero test events" case: the data file genuinely exists (empty) alongside the
   * marker - matching what the real writer's constructor always does before anything else.
   */
  @Test
  void treatsAnEmptyButExistingDataFileWithAMarkerAsValid(@TempDir Path dir) throws IOException {
    String runId = "run-1";
    Path dataFile = dir.resolve(runId + ".tests.jsonl");
    Path marker = dir.resolve(runId + ".tests.complete");
    Files.createFile(dataFile);
    Files.createFile(marker);
    RecordingRunEventAppender appender = new RecordingRunEventAppender();

    ListenerEventIngestor ingestor = newIngestor(runId, dataFile, marker, appender);
    IngestionResult result = ingestor.stopAndAwaitFinished(DRAIN_TIMEOUT);

    assertThat(result.valid()).isTrue();
    assertThat(result.sawCompletionMarker()).isTrue();
    assertThat(appender.eventsFor(runId)).isEmpty();
  }

  /**
   * Regression test for the review's finding: the marker is only ever created after the writer
   * closes cleanly, so a trailing, never-terminated line coexisting with it can only mean
   * corruption - unlike the tolerated case above (stopped without a marker), this must fail.
   */
  @Test
  void rejectsATrailingIncompleteLineWhenTheMarkerExists(@TempDir Path dir) throws IOException {
    String runId = "run-1";
    Path dataFile = dir.resolve(runId + ".tests.jsonl");
    Path marker = dir.resolve(runId + ".tests.complete");
    String completeLine =
        OBJECT_MAPPER.writeValueAsString(RunnerEvent.testStarted(runId, 1, NOW, "t1", "test one"));
    Files.writeString(dataFile, completeLine + "\n{\"trunc", StandardCharsets.UTF_8);
    Files.createFile(marker);
    RecordingRunEventAppender appender = new RecordingRunEventAppender();

    ListenerEventIngestor ingestor = newIngestor(runId, dataFile, marker, appender);
    IngestionResult result = ingestor.stopAndAwaitFinished(DRAIN_TIMEOUT);

    assertThat(result.valid()).isFalse();
    assertThat(result.detail()).contains("unterminated trailing line");
  }

  @Test
  void rejectsAnUnsupportedSchemaVersion(@TempDir Path dir) throws IOException {
    String runId = "run-1";
    Path dataFile = dir.resolve(runId + ".tests.jsonl");
    Path marker = dir.resolve(runId + ".tests.complete");
    RunnerEvent wrongVersion = RunnerEvent.testStarted(runId, 1, NOW, "t1", "test one");
    String line =
        OBJECT_MAPPER
            .writeValueAsString(wrongVersion)
            .replace(
                "\"schemaVersion\":\"" + RunnerEvent.CURRENT_SCHEMA_VERSION + "\"",
                "\"schemaVersion\":\"999\"");
    Files.writeString(dataFile, line + "\n", StandardCharsets.UTF_8);
    RecordingRunEventAppender appender = new RecordingRunEventAppender();

    ListenerEventIngestor ingestor = newIngestor(runId, dataFile, marker, appender);
    IngestionResult result = ingestor.stopAndAwaitFinished(DRAIN_TIMEOUT);

    assertThat(result.valid()).isFalse();
    assertThat(result.detail()).contains("unsupported schemaVersion");
  }

  /**
   * Regression test for the review's finding: each byte chunk used to be decoded to a String
   * independently, so a multi-byte UTF-8 character split across a chunk boundary would silently
   * become a corrupted replacement character on each side. A tiny maxChunkBytes deterministically
   * forces the boundary to land inside a multi-byte character's own bytes.
   */
  @Test
  void doesNotCorruptAMultiByteUtf8CharacterSplitAcrossAChunkBoundary(@TempDir Path dir)
      throws IOException {
    String runId = "run-1";
    Path dataFile = dir.resolve(runId + ".tests.jsonl");
    Path marker = dir.resolve(runId + ".tests.complete");
    // "café" - the "é" is a 2-byte UTF-8 sequence (0xC3 0xA9). A 1-byte chunk size guarantees some
    // read boundary lands between those two bytes.
    String displayName = "café 日本"; // also includes a 3-byte CJK character for good measure
    writeLines(dataFile, RunnerEvent.testStarted(runId, 1, NOW, "t1", displayName));
    Files.createFile(marker);
    RecordingRunEventAppender appender = new RecordingRunEventAppender();

    ListenerEventIngestor ingestor =
        new ListenerEventIngestor(runId, dataFile, marker, appender, OBJECT_MAPPER, SHORT_POLL, 1);
    IngestionResult result = ingestor.stopAndAwaitFinished(DRAIN_TIMEOUT);

    assertThat(result.valid()).isTrue();
    assertThat(appender.eventsFor(runId))
        .extracting(RunnerEvent::testDisplayName)
        .containsExactly(displayName);
  }

  /**
   * Regression test for the review's finding: {@code Future.cancel(true)} on a bare {@code
   * CompletableFuture.supplyAsync} task does not actually interrupt it, so a stuck poll loop would
   * keep running (and could still append an event) long after stopAndAwaitFinished gave up waiting.
   * A real {@code ExecutorService.submit} task must be genuinely interrupted instead.
   */
  @Test
  void aTimedOutIngestorNeverForwardsALateEventAfterward(@TempDir Path dir) throws Exception {
    String runId = "run-1";
    Path dataFile = dir.resolve(runId + ".tests.jsonl");
    Path marker = dir.resolve(runId + ".tests.complete");
    RecordingRunEventAppender appender = new RecordingRunEventAppender();
    ListenerEventIngestor ingestor =
        new ListenerEventIngestor(
            runId, dataFile, marker, appender, OBJECT_MAPPER, Duration.ofSeconds(10));
    Thread.sleep(200); // let the loop reach and enter its 10-second sleep before signalling stop

    IngestionResult result = ingestor.stopAndAwaitFinished(Duration.ofMillis(200));
    assertThat(result.valid()).isFalse();

    // If the background thread were still alive (not genuinely interrupted), it would pick this up
    // on its next wake and forward it - a generous window makes that failure mode observable.
    writeLines(dataFile, RunnerEvent.testStarted(runId, 1, NOW, "t1", "late"));
    Thread.sleep(500);
    assertThat(appender.eventsFor(runId)).isEmpty();
  }

  /**
   * A stuck poll loop must not let {@code stopAndAwaitFinished} block a run's finalization forever
   * - it is a managed {@link java.util.concurrent.Future} with a bounded {@code get}, not a raw
   * join.
   */
  @Test
  void awaitFinishedTimesOutIfThePollLoopCannotWakeInTime(@TempDir Path dir) throws Exception {
    String runId = "run-1";
    Path dataFile = dir.resolve(runId + ".tests.jsonl");
    Path marker = dir.resolve(runId + ".tests.complete");
    RecordingRunEventAppender appender = new RecordingRunEventAppender();
    ListenerEventIngestor ingestor =
        new ListenerEventIngestor(
            runId, dataFile, marker, appender, OBJECT_MAPPER, Duration.ofSeconds(10));
    // Deterministically let the background loop reach and enter its 10-second sleep before
    // signalling stop - otherwise the stop flag could be set before the loop's very first check,
    // letting it return immediately instead of exercising the timeout path this test targets.
    Thread.sleep(200);

    IngestionResult result = ingestor.stopAndAwaitFinished(Duration.ofMillis(200));

    assertThat(result.valid()).isFalse();
    assertThat(result.detail()).contains("did not stop within");
  }

  private ListenerEventIngestor newIngestor(
      String runId, Path dataFile, Path marker, RunEventAppender appender) {
    return new ListenerEventIngestor(runId, dataFile, marker, appender, OBJECT_MAPPER, SHORT_POLL);
  }

  private void writeLines(Path file, RunnerEvent... events) throws IOException {
    StringBuilder content = new StringBuilder();
    for (RunnerEvent event : events) {
      content.append(OBJECT_MAPPER.writeValueAsString(event)).append('\n');
    }
    Files.writeString(file, content.toString(), StandardCharsets.UTF_8);
  }

  private byte[] concatBytes(byte[]... chunks) {
    int total = 0;
    for (byte[] chunk : chunks) {
      total += chunk.length;
    }
    byte[] result = new byte[total];
    int offset = 0;
    for (byte[] chunk : chunks) {
      System.arraycopy(chunk, 0, result, offset, chunk.length);
      offset += chunk.length;
    }
    return result;
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  private void appendLine(Path file, RunnerEvent event) throws IOException {
    Files.writeString(
        file,
        OBJECT_MAPPER.writeValueAsString(event) + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.APPEND);
  }

  private void awaitEventCount(RecordingRunEventAppender appender, String runId, int expectedCount)
      throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(5);
    while (Instant.now().isBefore(deadline)) {
      if (appender.eventsFor(runId).size() >= expectedCount) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError(
        "Expected at least " + expectedCount + " events for " + runId + " within the deadline");
  }
}
