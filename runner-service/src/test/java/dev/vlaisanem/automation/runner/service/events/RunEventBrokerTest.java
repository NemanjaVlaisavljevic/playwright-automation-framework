package dev.vlaisanem.automation.runner.service.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunOutcome;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.exception.InvalidEventResumeSequenceException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunEventBrokerTest {

  private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
  private static final ObjectMapper OBJECT_MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Test
  void appendPublishesToAnAlreadyRegisteredLiveSubscriber(@TempDir Path dir) throws Exception {
    RunEventBroker broker = newBroker(dir);
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();
    broker.replayAndSubscribe("run-1", 0, subscriber);

    broker.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW));
    broker.append("run-1", seq -> RunnerEvent.runStarted("run-1", seq, NOW));

    awaitReceivedCount(subscriber, 2);
    assertThat(subscriber.received)
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.RUN_QUEUED, EventType.RUN_STARTED);
  }

  @Test
  void replayAndSubscribeDeliversExistingHistoryThenLiveEvents(@TempDir Path dir) throws Exception {
    RunEventBroker broker = newBroker(dir);
    broker.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW));
    broker.append("run-1", seq -> RunnerEvent.runStarted("run-1", seq, NOW));

    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();
    broker.replayAndSubscribe("run-1", 0, subscriber);
    broker.append(
        "run-1", seq -> RunnerEvent.runFinished("run-1", seq, NOW, RunOutcome.SUCCEEDED, null));

    awaitReceivedCount(subscriber, 3);
    assertThat(subscriber.received).extracting(RunnerEvent::sequence).containsExactly(1L, 2L, 3L);
  }

  @Test
  void replayAfterASequenceOnlyReplaysNewerEvents(@TempDir Path dir) throws Exception {
    RunEventBroker broker = newBroker(dir);
    broker.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW));
    broker.append("run-1", seq -> RunnerEvent.runStarted("run-1", seq, NOW));

    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();
    broker.replayAndSubscribe("run-1", 1, subscriber); // already saw sequence 1

    awaitReceivedCount(subscriber, 1);
    assertThat(subscriber.received).extracting(RunnerEvent::sequence).containsExactly(2L);
  }

  /**
   * Stress-test companion to {@link
   * #replayAndSubscribeBlocksAConcurrentAppendUntilTheSubscriberIsRegistered} - that test proves
   * the atomicity guarantee deterministically; this one hammers the same guarantee under real
   * concurrent load (300 racing appends) to catch anything a single deterministic interleaving
   * could miss. A subscriber that starts via {@code replayAndSubscribe} while {@code append} calls
   * are continuously racing on another thread must still receive every event exactly once, in
   * order, with no gap and no duplicate.
   */
  @Test
  void replayAndSubscribeNeverMissesOrDuplicatesAnEventRacingConcurrently(@TempDir Path dir)
      throws Exception {
    RunEventBroker broker = newBroker(dir);
    int totalEvents = 300;
    ExecutorService publisher = Executors.newSingleThreadExecutor();

    Future<?> publishing =
        publisher.submit(
            () -> {
              for (int i = 0; i < totalEvents; i++) {
                broker.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW));
              }
            });

    // Let a handful of appends land first, so there is genuine history to replay, then subscribe
    // while publishing continues concurrently on the other thread.
    Thread.sleep(10);
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();
    broker.replayAndSubscribe("run-1", 0, subscriber);

    publishing.get(30, TimeUnit.SECONDS);
    publisher.shutdown();
    awaitReceivedCount(subscriber, totalEvents);

    assertThat(subscriber.received)
        .extracting(RunnerEvent::sequence)
        .containsExactlyElementsOf(LongStream.rangeClosed(1, totalEvents).boxed().toList());
  }

  /**
   * Regression test for the review's finding: a slow-consumer disconnect is detected inside {@link
   * RunEventHub.Subscription#offerLive}, on whatever thread calls {@link RunEventBroker#append} -
   * here, this test's own thread. If the subscriber's {@code onError} callback ran synchronously on
   * that path and threw, the exception would propagate out of {@code append} even though the event
   * was already durably written to the journal. Proving {@code append} never throws here, and that
   * every appended event is still readable afterward, is what proves the callback is fully
   * decoupled from the publisher.
   */
  @Test
  void aSubscriberErrorCallbackThatThrowsNeverFailsAppendOrCorruptsTheJournal(@TempDir Path dir)
      throws Exception {
    FileBackedRunEventJournal journal = newJournal(dir);
    RunEventBroker broker = newBroker(journal);
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();
    subscriber.blockOnEvent = true; // never drains, so the live mailbox eventually overflows
    subscriber.throwOnErrorCallback = true;
    broker.replayAndSubscribe("run-1", 0, subscriber);

    for (long seq = 1; seq <= 260; seq++) { // comfortably exceeds the live capacity (256)
      assertThatCode(() -> broker.append("run-1", s -> RunnerEvent.runQueued("run-1", s, NOW)))
          .doesNotThrowAnyException();
    }

    assertThat(subscriber.errorLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(subscriber.error).hasMessageContaining("mailbox full");
    assertThat(journal.readAfter("run-1", 0)).hasSize(260);
  }

  /**
   * Deterministic replacement for a timing-based race test: a custom {@link RunEventReader} blocks
   * mid-read, while the broker's per-run lock is held, so this test can prove - not just hope -
   * that a concurrent {@link RunEventBroker#append} cannot complete until the reader is released
   * and {@code replayAndSubscribe} has registered the subscriber. Once released, the subscriber
   * must see exactly the pre-existing replay event followed by the one concurrent live append, with
   * no gap and no duplicate.
   */
  @Test
  void replayAndSubscribeBlocksAConcurrentAppendUntilTheSubscriberIsRegistered(@TempDir Path dir)
      throws Exception {
    FileBackedRunEventJournal journal = newJournal(dir);
    journal.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW)); // pre-existing
    BlockingReader blockingReader = new BlockingReader(journal);
    RunEventBroker broker = new RunEventBroker(journal, blockingReader, testProperties());
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<RunEventSubscription> subscribing =
          executor.submit(() -> broker.replayAndSubscribe("run-1", 0, subscriber));
      assertThat(blockingReader.entered.await(5, TimeUnit.SECONDS)).isTrue();

      Future<RunnerEvent> appending =
          executor.submit(
              () -> broker.append("run-1", seq -> RunnerEvent.runStarted("run-1", seq, NOW)));

      // The per-run lock is still held by the blocked replayAndSubscribe call - the concurrent
      // append must not be able to finish yet.
      assertThatThrownBy(() -> appending.get(300, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      blockingReader.release.countDown();

      subscribing.get(5, TimeUnit.SECONDS);
      appending.get(5, TimeUnit.SECONDS);

      awaitReceivedCount(subscriber, 2);
      assertThat(subscriber.received).extracting(RunnerEvent::sequence).containsExactly(1L, 2L);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Regression test for the review's finding: resuming from a sequence the journal never produced
   * (a client's {@code Last-Event-ID} claiming to have seen something that does not exist) must be
   * rejected outright, not silently served as if it were {@code 0} or the latest.
   */
  @Test
  void replayAndSubscribeRejectsAnAfterSequenceAheadOfTheJournal(@TempDir Path dir) {
    RunEventBroker broker = newBroker(dir);
    broker.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW));
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();

    assertThatThrownBy(() -> broker.replayAndSubscribe("run-1", 100, subscriber))
        .isInstanceOf(InvalidEventResumeSequenceException.class)
        .hasMessageContaining("100")
        .hasMessageContaining("1");
  }

  /** Same finding, for a runId the journal has no record of at all - not just a stale one. */
  @Test
  void replayAndSubscribeRejectsAnAfterSequenceForAnUnknownRun(@TempDir Path dir) {
    RunEventBroker broker = newBroker(dir);
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();

    assertThatThrownBy(() -> broker.replayAndSubscribe("never-seen", 1, subscriber))
        .isInstanceOf(InvalidEventResumeSequenceException.class);
  }

  /**
   * Regression test for the review's finding: a run the journal has no record of at all still
   * accepts a resume point of {@code 0} (the "give me everything" sentinel) without being rejected
   * as "ahead of the journal" - {@code 0} is never ahead of anything.
   */
  @Test
  void replayAndSubscribeWithZeroIsNeverRejectedEvenForAnUnknownRun(@TempDir Path dir) {
    RunEventBroker broker = newBroker(dir);
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();

    assertThatCode(() -> broker.replayAndSubscribe("never-seen", 0, subscriber))
        .doesNotThrowAnyException();
  }

  /**
   * Regression test for the review's finding: a client reconnecting exactly at a terminal run's
   * last sequence has nothing left to replay and nothing more will ever be appended - the stream
   * must complete immediately rather than sit open until the emitter's own timeout.
   */
  @Test
  void replayAndSubscribeAtExactlyTheLatestTerminalSequenceCompletesImmediately(@TempDir Path dir)
      throws Exception {
    RunEventBroker broker = newBroker(dir);
    broker.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW));
    broker.append(
        "run-1", seq -> RunnerEvent.runFinished("run-1", seq, NOW, RunOutcome.SUCCEEDED, null));
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();

    broker.replayAndSubscribe("run-1", 2, subscriber);

    assertThat(subscriber.completedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(subscriber.received).isEmpty();
  }

  /**
   * Contrast case: resuming exactly at the latest sequence of a run that is NOT yet terminal must
   * stay open - more events may still be appended, unlike the terminal case above.
   */
  @Test
  void replayAndSubscribeAtExactlyTheLatestNonTerminalSequenceStaysOpen(@TempDir Path dir)
      throws Exception {
    RunEventBroker broker = newBroker(dir);
    broker.append("run-1", seq -> RunnerEvent.runQueued("run-1", seq, NOW));
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();

    broker.replayAndSubscribe("run-1", 1, subscriber);

    assertThat(subscriber.completedLatch.await(300, TimeUnit.MILLISECONDS)).isFalse();
  }

  /**
   * Definition-of-done item for the SSE layer: {@code @PreDestroy} must actually reach the hub and
   * close whatever subscriptions are still active, rather than the broker just discarding them
   * silently on shutdown.
   */
  @Test
  void shutdownClosesActiveSubscriptions(@TempDir Path dir) throws Exception {
    RunEventBroker broker = newBroker(dir);
    RunEventHubTest.RecordingSubscriber subscriber = new RunEventHubTest.RecordingSubscriber();
    broker.replayAndSubscribe("run-1", 0, subscriber);

    broker.shutdown();

    assertThat(subscriber.completedLatch.await(5, TimeUnit.SECONDS)).isTrue();
  }

  private RunEventBroker newBroker(Path dir) {
    return newBroker(newJournal(dir));
  }

  private FileBackedRunEventJournal newJournal(Path dir) {
    RunnerProperties properties =
        new RunnerProperties(
            ".",
            Duration.ofSeconds(30),
            dir.resolve("raw").toString(),
            dir.resolve("journal").toString(),
            dir.resolve("logs").toString(),
            1024 * 1024,
            Duration.ofSeconds(5),
            Duration.ofSeconds(1),
            1,
            Duration.ofMillis(150),
            Duration.ofSeconds(5),
            10_000,
            Duration.ofSeconds(15),
            Duration.ofMinutes(10));
    return new FileBackedRunEventJournal(properties, OBJECT_MAPPER);
  }

  private RunEventBroker newBroker(FileBackedRunEventJournal journal) {
    return new RunEventBroker(journal, journal, testProperties());
  }

  /**
   * A minimal-but-valid properties object for constructing a broker directly - only {@code
   * sseMaxSubscribers()} is ever read from it, so the directory fields are dummy values, not tied
   * to any {@code @TempDir}.
   */
  private RunnerProperties testProperties() {
    return new RunnerProperties(
        ".",
        Duration.ofSeconds(30),
        "raw",
        "journal",
        "logs",
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

  /**
   * Test double that blocks inside {@code latest} until released, so a test can deterministically
   * prove the broker's per-run lock is actually held for the whole "read replay snapshot" step -
   * not just usually working out under timing that happens to favor it. Blocks in {@code latest}
   * specifically because {@link RunEventBroker#replayAndSubscribe} calls that first, to validate
   * the resume point against the current high-water mark before ever reading the replay batch.
   */
  private static final class BlockingReader implements RunEventReader {
    private final RunEventReader delegate;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    private BlockingReader(RunEventReader delegate) {
      this.delegate = delegate;
    }

    @Override
    public List<RunnerEvent> readAfter(String runId, long afterSequence) {
      return delegate.readAfter(runId, afterSequence);
    }

    @Override
    public Optional<RunnerEvent> latest(String runId) {
      entered.countDown();
      try {
        release.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      return delegate.latest(runId);
    }
  }

  private void awaitReceivedCount(RunEventHubTest.RecordingSubscriber subscriber, int expected)
      throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(10);
    while (Instant.now().isBefore(deadline)) {
      if (subscriber.received.size() >= expected) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Expected " + expected + " events, got " + subscriber.received.size());
  }
}
