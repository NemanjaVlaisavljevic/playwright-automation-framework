package dev.vlaisanem.automation.runner.service.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.vlaisanem.automation.runner.service.api.RunEventStreamController.DeferredSubscriptionHandle;
import dev.vlaisanem.automation.runner.service.events.RunEventSubscription;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the review's finding: {@code replayAndSubscribe} can already be delivering
 * events - and therefore racing toward its own close - before it returns a {@link
 * RunEventSubscription} handle, so a completion/timeout/error that fires that early must not be
 * lost just because the handle has not been set yet.
 */
class DeferredSubscriptionHandleTest {

  @Test
  void closesImmediatelyWhenRequestedAfterTheSubscriptionIsAlreadySet() {
    DeferredSubscriptionHandle handle = new DeferredSubscriptionHandle();
    RunEventSubscription subscription = mock(RunEventSubscription.class);
    AtomicInteger onCloseCalls = new AtomicInteger();
    handle.onClose(onCloseCalls::incrementAndGet);

    handle.set(subscription);
    handle.requestClose();

    verify(subscription, times(1)).close();
    assertThat(onCloseCalls.get()).isEqualTo(1);
  }

  /**
   * The exact race the review flagged: a completion/timeout/error fires before {@code
   * replayAndSubscribe} has returned anything to {@link #set}. The close must be deferred, not
   * lost.
   */
  @Test
  void aRequestBeforeTheSubscriptionIsSetIsHonoredAsSoonAsItArrives() {
    DeferredSubscriptionHandle handle = new DeferredSubscriptionHandle();
    RunEventSubscription subscription = mock(RunEventSubscription.class);
    AtomicInteger onCloseCalls = new AtomicInteger();
    handle.onClose(onCloseCalls::incrementAndGet);

    handle.requestClose();
    verify(subscription, never()).close();
    assertThat(onCloseCalls.get()).isZero();

    handle.set(subscription);

    verify(subscription, times(1)).close();
    assertThat(onCloseCalls.get()).isEqualTo(1);
  }

  @Test
  void aRequestWithNoSubscriptionEverSetNeitherClosesNorRunsOnClose() {
    DeferredSubscriptionHandle handle = new DeferredSubscriptionHandle();
    AtomicInteger onCloseCalls = new AtomicInteger();
    handle.onClose(onCloseCalls::incrementAndGet);

    assertThatCode(handle::requestClose).doesNotThrowAnyException();
    assertThat(onCloseCalls.get()).isZero();
  }

  @Test
  void requestCloseIsIdempotent() {
    DeferredSubscriptionHandle handle = new DeferredSubscriptionHandle();
    RunEventSubscription subscription = mock(RunEventSubscription.class);
    handle.set(subscription);

    handle.requestClose();
    handle.requestClose();
    handle.requestClose();

    verify(subscription, times(1)).close();
  }

  /**
   * Deterministic proof that exactly one of {@link DeferredSubscriptionHandle#set} / {@link
   * DeferredSubscriptionHandle#requestClose} ever performs the actual close, however they are
   * interleaved - not just under timing that happens to favor one ordering.
   */
  @Test
  void concurrentSetAndRequestCloseNeverDoubleCloseAndNeverLoseTheClose() throws Exception {
    int iterations = 500;
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < iterations; i++) {
        DeferredSubscriptionHandle handle = new DeferredSubscriptionHandle();
        RunEventSubscription subscription = mock(RunEventSubscription.class);
        AtomicInteger onCloseCalls = new AtomicInteger();
        handle.onClose(onCloseCalls::incrementAndGet);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<?> setTask =
            executor.submit(
                () -> {
                  ready.countDown();
                  awaitUninterruptibly(start);
                  handle.set(subscription);
                });
        Future<?> closeTask =
            executor.submit(
                () -> {
                  ready.countDown();
                  awaitUninterruptibly(start);
                  handle.requestClose();
                });

        ready.await();
        start.countDown();
        setTask.get(5, TimeUnit.SECONDS);
        closeTask.get(5, TimeUnit.SECONDS);

        verify(subscription, times(1)).close();
        assertThat(onCloseCalls.get()).isEqualTo(1);
      }
    } finally {
      executor.shutdown();
      assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
