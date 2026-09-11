package dev.vlaisanem.automation.runner.service.orchestration;

import dev.vlaisanem.automation.runner.service.events.ListenerEventIngestor;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
  private final CountDownLatch processPublished = new CountDownLatch(1);
  private final AtomicReference<Runnable> queuedTask = new AtomicReference<>();
  private final AtomicReference<ListenerEventIngestor> ingestor = new AtomicReference<>();
  private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
  private final Object workerLock = new Object();
  private Thread workerThread;

  AtomicReference<Process> process() {
    return process;
  }

  /**
   * Publishes the launched process - must be called exactly once per run, after its ingestor is
   * already attached (see {@code RunService.executeRun}'s own publish-ordering comment).
   */
  void publishProcess(Process launched) {
    process.set(launched);
    processPublished.countDown();
  }

  /**
   * Blocks up to {@code timeout} for {@link #publishProcess}, then returns whatever is present
   * (possibly still {@code null} if the worker recorded a terminal status first). Closes the window
   * where {@code find()} already reports {@code RUNNING} but the process isn't attached yet for a
   * concurrent {@code cancel()} to terminate.
   */
  Process awaitProcessPublished(Duration timeout) {
    Process current = process.get();
    if (current != null) {
      return current;
    }
    try {
      processPublished.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    return process.get();
  }

  /**
   * Exposed so {@code cancel()} can stop and drain this run's ingestor itself when process
   * termination fails and it must emit an emergency terminal event without waiting on the worker
   * thread, which may be stuck indefinitely in {@code awaitCompletion}.
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
   * Records the executor worker thread running {@code executeRun} for this run. {@code
   * ThreadPoolExecutor} reuses the same {@link Thread} for later, unrelated runs, so {@link
   * #interruptWorkerIfAttached} and {@link #detachWorker} share {@code workerLock} to keep a cancel
   * racing this run's tail from interrupting whatever run that thread picks up next.
   */
  void attachWorker(Thread thread) {
    synchronized (workerLock) {
      workerThread = thread;
    }
  }

  /**
   * Interrupts the attached worker thread, if any. Synchronized against {@link #detachWorker} so a
   * late interrupt can never land on a thread that has since moved on to a different run.
   */
  void interruptWorkerIfAttached() {
    synchronized (workerLock) {
      if (workerThread != null) {
        workerThread.interrupt();
      }
    }
  }

  /**
   * Detaches the worker thread - call from {@code executeRun}'s {@code finally} with {@code
   * Thread.currentThread()}. Only clears the reference if it still matches {@code thread}.
   */
  void detachWorker(Thread thread) {
    synchronized (workerLock) {
      if (workerThread == thread) {
        workerThread = null;
      }
    }
  }
}
