package dev.vlaisanem.automation.runner.service.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.vlaisanem.automation.runner.service.api.RunEventStreamController.EmitterGuard;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.MockingDetails;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Spring's {@code ResponseBodyEmitter} does not tolerate completing an already-finished async
 * context a second time. Both a container-driven event (client disconnect/timeout) and a hub-driven
 * one (the subscription closing on its own) can race to finish the same emitter, so this guard must
 * let exactly one of them through - see {@link RunEventStreamController} class Javadoc.
 */
class EmitterGuardTest {

  @Test
  void completeIsANoOpTheSecondTime() {
    SseEmitter emitter = mock(SseEmitter.class);
    EmitterGuard guard = new EmitterGuard(emitter);

    guard.complete();
    guard.complete();

    verify(emitter, times(1)).complete();
  }

  @Test
  void completeWithErrorIsANoOpTheSecondTime() {
    SseEmitter emitter = mock(SseEmitter.class);
    EmitterGuard guard = new EmitterGuard(emitter);
    RuntimeException cause = new RuntimeException("boom");

    guard.completeWithError(cause);
    guard.completeWithError(cause);

    verify(emitter, times(1)).completeWithError(cause);
  }

  @Test
  void whicheverCompletionMethodRunsFirstWins() {
    SseEmitter emitter = mock(SseEmitter.class);
    EmitterGuard guard = new EmitterGuard(emitter);

    guard.complete();
    guard.completeWithError(new RuntimeException("too late"));

    verify(emitter, times(1)).complete();
    verify(emitter, never()).completeWithError(any());
  }

  @Test
  void theOtherOrderingAlsoLetsOnlyTheFirstOneThrough() {
    SseEmitter emitter = mock(SseEmitter.class);
    EmitterGuard guard = new EmitterGuard(emitter);
    RuntimeException cause = new RuntimeException("first");

    guard.completeWithError(cause);
    guard.complete();

    verify(emitter, times(1)).completeWithError(cause);
    verify(emitter, never()).complete();
  }

  /**
   * Deterministic proof that concurrent completion attempts - exactly the container-vs-hub race
   * this class exists for - never both reach the underlying emitter, however they are interleaved.
   */
  @Test
  void concurrentCompletionAttemptsNeverBothReachTheEmitter() throws Exception {
    int iterations = 500;
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < iterations; i++) {
        SseEmitter emitter = mock(SseEmitter.class);
        EmitterGuard guard = new EmitterGuard(emitter);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<?> completeTask =
            executor.submit(
                () -> {
                  ready.countDown();
                  awaitUninterruptibly(start);
                  guard.complete();
                });
        Future<?> errorTask =
            executor.submit(
                () -> {
                  ready.countDown();
                  awaitUninterruptibly(start);
                  guard.completeWithError(new RuntimeException("racing"));
                });

        ready.await();
        start.countDown();
        completeTask.get(5, TimeUnit.SECONDS);
        errorTask.get(5, TimeUnit.SECONDS);

        int totalCompletions = countInvocations(mockingDetails(emitter));
        assertThat(totalCompletions).isEqualTo(1);
      }
    } finally {
      executor.shutdown();
      assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static int countInvocations(MockingDetails details) {
    long completeCalls =
        details.getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("complete"))
            .count();
    long completeWithErrorCalls =
        details.getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("completeWithError"))
            .count();
    return (int) (completeCalls + completeWithErrorCalls);
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
