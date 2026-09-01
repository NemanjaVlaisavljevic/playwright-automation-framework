package dev.vlaisanem.automation.runner.service.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vlaisanem.automation.runner.contract.RunOutcome;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.exception.RunEventSubscriptionRejectedException;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class RunEventHubTest {

  private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

  /**
   * Large enough that no test other than the capacity-specific ones below is ever limited by it.
   */
  private static final int UNBOUNDED_FOR_TESTS = 10_000;

  private final RunEventHub hub = new RunEventHub(UNBOUNDED_FOR_TESTS);

  @Test
  void deliversLiveEventsInOrder() throws Exception {
    RecordingSubscriber subscriber = new RecordingSubscriber();
    RunEventSubscription subscription = hub.subscribe("run-1", List.of(), subscriber);

    hub.publish(RunnerEvent.runQueued("run-1", 1, NOW));
    hub.publish(RunnerEvent.runStarted("run-1", 2, NOW));

    awaitReceivedCount(subscriber, 2);
    assertThat(subscriber.received).extracting(RunnerEvent::sequence).containsExactly(1L, 2L);
    subscription.close();
  }

  /**
   * Regression test for the review's finding: seeding the mailbox with the replay batch before
   * registering for live publish means a live event arriving immediately after subscribe() still
   * cannot overtake the replay - both are drained by the same single delivery thread, in queue
   * order.
   */
  @Test
  void deliversReplayBeforeAnyLiveEvent() throws Exception {
    RecordingSubscriber subscriber = new RecordingSubscriber();
    List<RunnerEvent> replay =
        List.of(RunnerEvent.runQueued("run-1", 1, NOW), RunnerEvent.runStarted("run-1", 2, NOW));

    hub.subscribe("run-1", replay, subscriber);
    hub.publish(RunnerEvent.runQueued("run-1", 3, NOW));

    awaitReceivedCount(subscriber, 3);
    assertThat(subscriber.received).extracting(RunnerEvent::sequence).containsExactly(1L, 2L, 3L);
  }

  @Test
  void publishToARunWithNoSubscribersIsANoOp() {
    assertThatCode(() -> hub.publish(RunnerEvent.runQueued("no-subscribers", 1, NOW)))
        .doesNotThrowAnyException();
  }

  @Test
  void closeNotifiesOnCompleteAndStopsFurtherDelivery() throws Exception {
    RecordingSubscriber subscriber = new RecordingSubscriber();
    RunEventSubscription subscription = hub.subscribe("run-1", List.of(), subscriber);

    subscription.close();

    assertThat(subscriber.completedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    hub.publish(RunnerEvent.runQueued("run-1", 1, NOW));
    Thread.sleep(200);
    assertThat(subscriber.received).isEmpty();
  }

  @Test
  void aDeliveryFailureClosesTheSubscriptionWithOnError() throws Exception {
    RecordingSubscriber subscriber = new RecordingSubscriber();
    subscriber.throwOnEvent = true;
    hub.subscribe("run-1", List.of(), subscriber);

    hub.publish(RunnerEvent.runQueued("run-1", 1, NOW));

    assertThat(subscriber.errorLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(subscriber.error).hasMessageContaining("simulated delivery failure");
  }

  /**
   * Regression test for the review's finding (mirrored from the plan): a slow subscriber must be
   * disconnected outright rather than blocking the publisher or growing its mailbox without bound.
   */
  @Test
  void aFullMailboxDisconnectsTheSlowSubscriberInsteadOfGrowingForever() throws Exception {
    RecordingSubscriber subscriber = new RecordingSubscriber();
    subscriber.blockOnEvent = true; // never actually drains, so the mailbox fills up
    hub.subscribe("run-1", List.of(), subscriber);

    for (long seq = 1; seq <= 300; seq++) { // comfortably exceeds the hub's live capacity (256)
      hub.publish(RunnerEvent.runQueued("run-1", seq, NOW));
    }

    assertThat(subscriber.errorLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(subscriber.error).hasMessageContaining("mailbox full");
  }

  /**
   * Regression test for the review's finding: replay is unbounded and must never count against the
   * live capacity - a subscriber seeded with a replay backlog far larger than the live capacity
   * must not be disconnected by a single live event that arrives right after it subscribes.
   */
  @Test
  void replayBacklogDoesNotCountTowardTheLiveMailboxLimit() throws Exception {
    RecordingSubscriber subscriber = new RecordingSubscriber();
    subscriber.blockOnEvent = true; // delivery blocks forever on the first item, so the mailbox
    // never drains - isolating the live-count check from actual delivery timing.
    List<RunnerEvent> replay = new ArrayList<>();
    for (long seq = 1; seq <= 300; seq++) { // comfortably exceeds the live capacity (256)
      replay.add(RunnerEvent.runQueued("run-1", seq, NOW));
    }
    hub.subscribe("run-1", replay, subscriber);

    assertThatCode(() -> hub.publish(RunnerEvent.runQueued("run-1", 301, NOW)))
        .doesNotThrowAnyException();
    assertThat(subscriber.errorLatch.await(300, TimeUnit.MILLISECONDS)).isFalse();
  }

  /**
   * Each subscription holds its own dedicated delivery thread for its whole lifetime, so the hub
   * enforces a hard cap on how many can be active at once rather than letting an unbounded number
   * of concurrent SSE clients create an unbounded number of threads.
   */
  @Test
  void subscribeRejectsOnceTheConfiguredCapacityIsReached() {
    RunEventHub boundedHub = new RunEventHub(1);
    RecordingSubscriber first = new RecordingSubscriber();
    RecordingSubscriber second = new RecordingSubscriber();
    boundedHub.subscribe("run-1", List.of(), first);

    assertThatThrownBy(() -> boundedHub.subscribe("run-2", List.of(), second))
        .isInstanceOf(RunEventSubscriptionRejectedException.class)
        .hasMessageContaining("Maximum of 1");
  }

  /** A rejected subscribe attempt must not itself count against the capacity it was rejected by. */
  @Test
  void aRejectedSubscribeDoesNotPermanentlyConsumeACapacitySlot() throws Exception {
    RunEventHub boundedHub = new RunEventHub(1);
    RecordingSubscriber first = new RecordingSubscriber();
    RunEventSubscription firstSubscription = boundedHub.subscribe("run-1", List.of(), first);
    assertThatThrownBy(() -> boundedHub.subscribe("run-2", List.of(), new RecordingSubscriber()))
        .isInstanceOf(RunEventSubscriptionRejectedException.class);

    firstSubscription.close();
    assertThat(first.completedLatch.await(5, TimeUnit.SECONDS)).isTrue();

    RecordingSubscriber third = new RecordingSubscriber();
    assertThatCode(() -> boundedHub.subscribe("run-3", List.of(), third))
        .doesNotThrowAnyException();
  }

  /**
   * Definition-of-done item for the SSE layer: shutting down the hub must close every active
   * subscription (each still gets its normal onComplete callback) and refuse any new one afterward,
   * so an application shutdown does not leave dangling subscriber threads or half-open SSE
   * responses behind.
   */
  @Test
  void shutdownClosesEveryActiveSubscriptionAndRejectsFurtherOnes() throws Exception {
    RecordingSubscriber first = new RecordingSubscriber();
    RecordingSubscriber second = new RecordingSubscriber();
    hub.subscribe("run-1", List.of(), first);
    hub.subscribe("run-2", List.of(), second);

    hub.shutdown();

    assertThat(first.completedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(second.completedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThatThrownBy(() -> hub.subscribe("run-3", List.of(), new RecordingSubscriber()))
        .isInstanceOf(RunEventSubscriptionRejectedException.class)
        .hasMessageContaining("shutting down");
  }

  /**
   * Regression test for the review's P1 finding: {@code subscribe()} racing {@code shutdown()} used
   * to be able to register a subscription after shutdown had already taken its close-everything
   * snapshot, leaking it forever. The {@code beforeSubscribeRegistration} test seam pauses a
   * subscribe call while it still holds the lifecycle read lock, mid-registration - {@code
   * shutdown()}'s write-lock acquisition must therefore block until that subscribe finishes and
   * releases it, proving the two can never interleave the way the finding described.
   */
  @Test
  void shutdownCannotMissASubscriptionThatIsMidRegistration() throws Exception {
    CountDownLatch subscribeEnteredCriticalSection = new CountDownLatch(1);
    CountDownLatch releaseSubscribe = new CountDownLatch(1);
    RunEventHub testHub =
        new RunEventHub(UNBOUNDED_FOR_TESTS) {
          @Override
          void beforeSubscribeRegistration() {
            subscribeEnteredCriticalSection.countDown();
            awaitUninterruptibly(releaseSubscribe);
          }
        };
    RecordingSubscriber subscriber = new RecordingSubscriber();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<RunEventSubscription> subscribing =
          executor.submit(() -> testHub.subscribe("run-1", List.of(), subscriber));
      assertThat(subscribeEnteredCriticalSection.await(5, TimeUnit.SECONDS)).isTrue();

      Future<?> shuttingDown = executor.submit(testHub::shutdown);
      // subscribe still holds the lifecycle read lock, paused mid-registration - shutdown's write
      // lock must not be grantable yet.
      assertThatThrownBy(() -> shuttingDown.get(300, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      releaseSubscribe.countDown();
      subscribing.get(5, TimeUnit.SECONDS);
      shuttingDown.get(5, TimeUnit.SECONDS);

      // shutdown could only proceed after subscribe fully registered, so its snapshot necessarily
      // included the new subscription - it must have been closed, not leaked.
      assertThat(subscriber.completedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Regression test for the review's P1 finding: {@code close()} racing {@code shutdown()} used to
   * be able to have its "submit to the terminal-notifier executor" step land after {@code
   * shutdown()} had already torn that executor down, throwing {@code RejectedExecutionException}
   * out through whatever thread called {@code close()} (e.g. a publisher thread mid-append). The
   * {@code beforeCloseNotify} test seam pauses a close call after it has already left the
   * bookkeeping maps but before it submits the terminal callback, still holding the lifecycle read
   * lock - {@code shutdown()}'s write-lock acquisition (needed before it can shut the executor
   * down) must therefore block until that close finishes.
   */
  @Test
  void shutdownCannotTearDownTheExecutorWhileACloseIsMidNotify() throws Exception {
    CountDownLatch closeEnteredCriticalSection = new CountDownLatch(1);
    CountDownLatch releaseClose = new CountDownLatch(1);
    RunEventHub testHub =
        new RunEventHub(UNBOUNDED_FOR_TESTS) {
          @Override
          void beforeCloseNotify() {
            closeEnteredCriticalSection.countDown();
            awaitUninterruptibly(releaseClose);
          }
        };
    RecordingSubscriber subscriber = new RecordingSubscriber();
    RunEventSubscription subscription = testHub.subscribe("run-1", List.of(), subscriber);

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> closing = executor.submit(subscription::close);
      assertThat(closeEnteredCriticalSection.await(5, TimeUnit.SECONDS)).isTrue();

      Future<?> shuttingDown = executor.submit(testHub::shutdown);
      assertThatThrownBy(() -> shuttingDown.get(300, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      releaseClose.countDown();
      closing.get(5, TimeUnit.SECONDS);
      shuttingDown.get(5, TimeUnit.SECONDS);

      // close's own notifyTerminal submit must have gone through before the executor was torn
      // down - no RejectedExecutionException should have escaped, and the callback still ran.
      assertThat(subscriber.completedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Regression test for the review's P2 finding: a subscription must not outlive the run's
   * canonical timeline - once it has delivered a live {@code RUN_FINISHED}, nothing more will ever
   * be published for that runId, so it should close itself immediately rather than sit open until a
   * client disconnect or the emitter's own timeout.
   */
  @Test
  void subscriptionClosesAutomaticallyAfterDeliveringALiveRunFinished() throws Exception {
    RecordingSubscriber subscriber = new RecordingSubscriber();
    hub.subscribe("run-1", List.of(), subscriber);

    hub.publish(RunnerEvent.runQueued("run-1", 1, NOW));
    hub.publish(RunnerEvent.runFinished("run-1", 2, NOW, RunOutcome.SUCCEEDED, null));

    assertThat(subscriber.completedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(subscriber.received).extracting(RunnerEvent::sequence).containsExactly(1L, 2L);
  }

  /**
   * Same as above, but for a client that connects after the run already finished and only replays.
   */
  @Test
  void subscriptionClosesAutomaticallyAfterReplayingATerminalRunFinished() throws Exception {
    RecordingSubscriber subscriber = new RecordingSubscriber();
    List<RunnerEvent> replay =
        List.of(
            RunnerEvent.runQueued("run-1", 1, NOW),
            RunnerEvent.runFinished("run-1", 2, NOW, RunOutcome.SUCCEEDED, null));

    hub.subscribe("run-1", replay, subscriber);

    assertThat(subscriber.completedLatch.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(subscriber.received).extracting(RunnerEvent::sequence).containsExactly(1L, 2L);
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private void awaitReceivedCount(RecordingSubscriber subscriber, int expected)
      throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(5);
    while (Instant.now().isBefore(deadline)) {
      if (subscriber.received.size() >= expected) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("Expected " + expected + " events, got " + subscriber.received.size());
  }

  static final class RecordingSubscriber implements RunEventSubscriber {
    final List<RunnerEvent> received = new CopyOnWriteArrayList<>();
    final CountDownLatch completedLatch = new CountDownLatch(1);
    final CountDownLatch errorLatch = new CountDownLatch(1);
    private final CountDownLatch blocker = new CountDownLatch(1);
    volatile Throwable error;
    volatile boolean throwOnEvent;
    volatile boolean blockOnEvent;
    volatile boolean throwOnErrorCallback;

    @Override
    public void onEvent(RunnerEvent event) throws Exception {
      if (blockOnEvent) {
        blocker.await();
        return;
      }
      if (throwOnEvent) {
        throw new IOException("simulated delivery failure");
      }
      received.add(event);
    }

    @Override
    public void onError(Throwable cause) {
      error = cause;
      errorLatch.countDown();
      if (throwOnErrorCallback) {
        throw new RuntimeException("simulated onError callback failure");
      }
    }

    @Override
    public void onComplete() {
      completedLatch.countDown();
    }
  }
}
