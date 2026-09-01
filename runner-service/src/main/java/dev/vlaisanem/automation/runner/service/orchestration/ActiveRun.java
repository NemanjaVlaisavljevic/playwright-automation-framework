package dev.vlaisanem.automation.runner.service.orchestration;

import dev.vlaisanem.automation.runner.service.events.ListenerEventIngestor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the live OS process (once launched) and cancel intent for one in-flight run, so a cancel
 * request on a different thread can find and kill it. {@code cancelRequested} is checked before
 * launching and right after, so a cancel that arrives while the run is still queued or mid-launch
 * still takes effect instead of being lost.
 */
final class ActiveRun {

  private final AtomicReference<Process> process = new AtomicReference<>();
  private final AtomicReference<Runnable> queuedTask = new AtomicReference<>();
  private final AtomicReference<ListenerEventIngestor> ingestor = new AtomicReference<>();
  private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
  private final Object workerLock = new Object();
  private Thread workerThread;

  AtomicReference<Process> process() {
    return process;
  }

  /**
   * Exposed so {@code cancel()}, running on a REST caller's thread, can stop and drain this run's
   * ingestor itself before finalizing it - e.g. when process termination fails and cancel() must
   * emit an emergency terminal event without waiting for the worker thread, which may be stuck
   * arbitrarily long inside {@code awaitCompletion} on the very process that would not die. Without
   * this, that emergency finalization could close the canonical journal while the ingestor is still
   * forwarding legitimately-occurred test events, silently dropping them.
   */
  AtomicReference<ListenerEventIngestor> ingestor() {
    return ingestor;
  }

  AtomicBoolean cancelRequested() {
    return cancelRequested;
  }

  AtomicReference<Runnable> queuedTask() {
    return queuedTask;
  }

  /**
   * Records the executor worker thread now running {@code executeRun} for this run. A {@code
   * ThreadPoolExecutor} can and does reuse the same {@link Thread} object for a later, unrelated
   * run once this one finishes - {@link #interruptWorkerIfAttached} and {@link #detachWorker} share
   * {@code workerLock} specifically so a cancel racing the tail end of this run can never interrupt
   * whichever different run that same thread picks up next.
   */
  void attachWorker(Thread thread) {
    synchronized (workerLock) {
      workerThread = thread;
    }
  }

  /**
   * Interrupts the currently attached worker thread, if this run's worker is still attached.
   * Synchronized against {@link #detachWorker} so the two can never interleave: either this runs
   * first and interrupts the real, still-owning thread, or {@code detachWorker} already cleared the
   * reference first and this becomes a safe no-op - there is no window where a late interrupt can
   * land on a thread that has since moved on to a different run's {@code executeRun}.
   */
  void interruptWorkerIfAttached() {
    synchronized (workerLock) {
      if (workerThread != null) {
        workerThread.interrupt();
      }
    }
  }

  /**
   * Detaches the worker thread - must be called from {@code executeRun}'s own {@code finally},
   * before the task returns to the pool, passing {@code Thread.currentThread()}. Only clears the
   * reference if it still matches {@code thread}, so a caller can never accidentally detach a
   * reference it does not itself own.
   */
  void detachWorker(Thread thread) {
    synchronized (workerLock) {
      if (workerThread == thread) {
        workerThread = null;
      }
    }
  }
}
