package dev.vlaisanem.automation.runner.service.events;

import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.exception.RunEventSubscriptionRejectedException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Fans a run's canonical events out to zero or more live subscribers. Never called directly from a
 * runner worker thread with anything that could block on slow I/O: {@link #publish} only enqueues
 * (a fast, non-blocking operation) into each subscriber's own mailbox - the actual delivery to
 * whatever transport a subscriber wraps (an SSE emitter, in the eventual controller) happens on
 * that subscriber's own dedicated thread, fully decoupled from the publisher.
 *
 * <p>No real backpressure on the live path: a subscriber that cannot keep up with live events is
 * disconnected outright rather than blocking the publisher or growing its mailbox without bound.
 * The replay batch a subscriber is seeded with (see {@link #subscribe}) is a separate, unbounded
 * concern - it is a known, finite history and is never counted against the live capacity, so a
 * subscriber with a large backlog to replay is never punished for it. The expected recovery from a
 * live disconnect is a client reconnect with {@code Last-Event-ID}, replaying via {@link
 * RunEventBroker#replayAndSubscribe}, not a slow-consumer protocol.
 *
 * <p>A subscription closes itself, cleanly, the moment it has delivered a {@link
 * EventType#RUN_FINISHED} event (replayed or live) - a run's canonical timeline never produces
 * anything after that (see {@link FileBackedRunEventJournal}'s own terminal contract), so there is
 * nothing left to justify holding its delivery thread, mailbox, and (for an SSE subscriber) HTTP
 * connection open any further.
 *
 * <p>A subscriber's terminal callback ({@link RunEventSubscriber#onError} / {@code onComplete})
 * always runs off whatever thread triggered the close, and any exception it throws is swallowed -
 * see {@link #notifyTerminal}. This matters most for the live-overflow disconnect, which is
 * detected directly on the publisher thread (ultimately whatever thread calls {@link
 * RunEventBroker#append}): a subscriber's own callback must never be able to block that thread, and
 * must never be able to turn an already-durably-written journal append into a thrown exception for
 * the caller.
 *
 * <p>Every subscriber holds its own dedicated delivery thread for the life of its connection, so
 * {@link #subscribe} enforces a hard cap ({@code maxSubscribers}) on how many can be active at once
 * - without it, an unbounded number of concurrent SSE clients would mean an unbounded number of
 * live threads. {@link #shutdown} closes every active subscription (each one's terminal callback
 * still runs exactly as it would from any other close) and stops accepting new ones, so an
 * application shutdown does not leave dangling subscriber threads or half-finished SSE responses.
 *
 * <p>{@code lifecycleLock} - a single {@link ReentrantReadWriteLock} shared by {@link #subscribe},
 * {@link Subscription#close}, and {@link #shutdown} - is what makes that guarantee actually hold
 * under concurrency, not just in the common case: {@code subscribe}/{@code close} take the shared
 * read lock (so any number of them proceed in parallel against each other), while {@code shutdown}
 * takes the exclusive write lock for its entire body. That serializes three things that used to
 * race: (1) a subscription created concurrently with shutdown can no longer end up neither rejected
 * nor included in shutdown's close-everything snapshot - it is provably one or the other; (2)
 * {@code close}'s "remove from bookkeeping, then submit to the terminal-notifier executor" can no
 * longer straddle {@code shutdown}'s executor teardown and throw a {@link
 * RejectedExecutionException} out through whatever thread called {@code close} (e.g. a publisher
 * thread, mid-{@code append}, exactly the kind of leak {@link #notifyTerminal} otherwise exists to
 * prevent) - critically, {@code close}'s idempotency check ({@code closed.compareAndSet}) has to
 * sit <em>inside</em> the locked section too, not just the work after it: claiming the close via
 * CAS and then blocking on the lock would let {@code shutdown}'s own snapshot loop see {@code
 * closed} already {@code true} for this subscription (a spurious no-op) while the real
 * unsubscribe/notify from the original caller is still pending, letting it fall through to a
 * torn-down executor once the lock is finally granted; (3) {@code shutdown} calling {@code close}
 * on each snapshotted subscription reentrantly acquires the very read lock it already excludes new
 * writers from - Java's {@code ReentrantReadWriteLock} explicitly supports this write-then-read
 * "downgrading" reentrancy for the same thread.
 *
 * <p>Package-private: {@link #subscribe} is only ever called by {@link RunEventBroker}, which alone
 * holds the per-run lock needed to combine it atomically with a replay snapshot. Calling it
 * directly would let a live event from {@link #publish} slip into a subscriber's mailbox before, or
 * interleaved with, that subscriber's own replay batch.
 */
class RunEventHub {

  private static final int LIVE_QUEUE_CAPACITY = 256;

  private final int maxSubscribers;
  private final Map<String, List<Subscription>> subscribersByRun = new ConcurrentHashMap<>();
  private final Set<Subscription> allSubscriptions = ConcurrentHashMap.newKeySet();
  private final AtomicInteger subscriberCount = new AtomicInteger();
  private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
  private volatile boolean shutdown = false;
  private final ExecutorService terminalNotifications =
      Executors.newCachedThreadPool(
          runnable -> {
            Thread thread = new Thread(runnable, "run-event-terminal-notifier");
            thread.setDaemon(true);
            return thread;
          });

  RunEventHub(int maxSubscribers) {
    this.maxSubscribers = maxSubscribers;
  }

  /**
   * Registers {@code subscriber} for {@code runId}, seeding its mailbox with {@code replayEvents}
   * first - entirely before it becomes visible to {@link #publish} - so replay and live delivery
   * can never interleave out of order for this subscriber, no matter how soon a live event arrives.
   *
   * @throws RunEventSubscriptionRejectedException if the hub has been shut down, or is already at
   *     {@code maxSubscribers} concurrent subscriptions.
   */
  Subscription subscribe(
      String runId, List<RunnerEvent> replayEvents, RunEventSubscriber subscriber) {
    lifecycleLock.readLock().lock();
    try {
      if (shutdown) {
        throw new RunEventSubscriptionRejectedException("Event hub is shutting down");
      }
      if (subscriberCount.incrementAndGet() > maxSubscribers) {
        subscriberCount.decrementAndGet();
        throw new RunEventSubscriptionRejectedException(
            "Maximum of " + maxSubscribers + " concurrent event subscribers reached");
      }
      beforeSubscribeRegistration();
      Subscription subscription = new Subscription(runId, subscriber, this);
      allSubscriptions.add(subscription);
      subscription.seedReplay(replayEvents);
      subscribersByRun
          .computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>())
          .add(subscription);
      subscription.start();
      return subscription;
    } finally {
      lifecycleLock.readLock().unlock();
    }
  }

  /** Enqueues {@code event} into every current subscriber of {@code event.runId()}'s mailbox. */
  void publish(RunnerEvent event) {
    List<Subscription> subscribers = subscribersByRun.get(event.runId());
    if (subscribers == null) {
      return;
    }
    for (Subscription subscription : subscribers) {
      subscription.offerLive(event);
    }
  }

  private void unsubscribe(Subscription subscription) {
    allSubscriptions.remove(subscription);
    subscriberCount.decrementAndGet();
    subscribersByRun.computeIfPresent(
        subscription.runId,
        (id, subscribers) -> {
          subscribers.remove(subscription);
          return subscribers.isEmpty() ? null : subscribers;
        });
  }

  /**
   * Stops accepting new subscriptions and closes every currently active one - each still gets its
   * normal terminal {@code onComplete()} callback (see {@link #notifyTerminal}), just triggered by
   * shutdown instead of a client disconnect or a slow-consumer overflow. Holding the exclusive
   * write lock for this whole method - snapshot, close-everything, and the executor's own {@code
   * shutdown()} call - is what guarantees no subscription can be created or closed concurrently in
   * a way that either escapes this snapshot or races the executor teardown (see the class Javadoc).
   * The bounded {@code awaitTermination} wait itself runs after releasing the lock: by that point
   * every relevant {@code close()} has either already finished or can no longer submit anything
   * new, so there is nothing left for that wait to race against.
   */
  void shutdown() {
    lifecycleLock.writeLock().lock();
    try {
      shutdown = true;
      for (Subscription subscription : List.copyOf(allSubscriptions)) {
        subscription.close();
      }
      terminalNotifications.shutdown();
    } finally {
      lifecycleLock.writeLock().unlock();
    }
    try {
      terminalNotifications.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Runs one subscriber's terminal callback on a dedicated notifier thread - never on the thread
   * that triggered the close - and swallows any exception it throws. Without this, a live-overflow
   * disconnect (detected inside {@link Subscription#offerLive}, on the publisher thread) would call
   * the callback synchronously there: a blocking or throwing callback would then stall or fail
   * {@link RunEventBroker#append} itself, after the event it just published had already been
   * durably written to the journal - silently splitting the repository/journal state from what the
   * caller believes happened. The {@link RejectedExecutionException} catch is a last-resort safety
   * net for the same failure mode via a different door: {@code lifecycleLock} (see the class
   * Javadoc) is what actually prevents a live {@code close()} from ever reaching a torn-down
   * executor, but dropping a callback here is still infinitely preferable to letting that exception
   * escape to a publisher thread if some future change ever reopens that gap.
   */
  private void notifyTerminal(RunEventSubscriber subscriber, Throwable cause) {
    try {
      terminalNotifications.submit(
          () -> {
            try {
              if (cause != null) {
                subscriber.onError(cause);
              } else {
                subscriber.onComplete();
              }
            } catch (RuntimeException callbackFailure) {
              // Deliberately swallowed - see method Javadoc. A broken subscriber callback must
              // never destabilize the hub, the broker, or any other subscriber.
            }
          });
    } catch (RejectedExecutionException rejected) {
      // Deliberately swallowed - see method Javadoc.
    }
  }

  /**
   * Test seam only - does nothing in production. Overridden in tests to pause exactly here,
   * deterministically reproducing {@code subscribe()} running concurrently, mid-registration, with
   * {@code shutdown()}'s snapshot - proving the lifecycle lock actually excludes that interleaving
   * rather than merely being expected to in the common case.
   */
  void beforeSubscribeRegistration() {}

  /**
   * Test seam only - does nothing in production. Overridden in tests to pause exactly here,
   * deterministically reproducing {@link Subscription#close} running concurrently, mid-notify, with
   * {@code shutdown()}'s executor teardown - proving the lifecycle lock actually excludes that
   * interleaving rather than merely being expected to in the common case.
   */
  void beforeCloseNotify() {}

  /** One subscriber's mailbox and dedicated single-thread delivery loop. */
  static final class Subscription implements RunEventSubscription {

    private final String runId;
    private final RunEventSubscriber subscriber;
    private final RunEventHub hub;
    private final BlockingQueue<Envelope> mailbox = new LinkedBlockingQueue<>();
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Counts only envelopes queued via the live path ({@link #offerLive}), never the replay batch
     * seeded at construction - replay is a known, finite history and must stay unbounded, while
     * only an unbounded, ever-growing live backlog indicates a subscriber that cannot keep up.
     */
    private final AtomicInteger liveQueued = new AtomicInteger();

    private Subscription(String runId, RunEventSubscriber subscriber, RunEventHub hub) {
      this.runId = runId;
      this.subscriber = subscriber;
      this.hub = hub;
      this.executor =
          Executors.newSingleThreadExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, "run-event-subscriber-" + runId);
                thread.setDaemon(true);
                return thread;
              });
    }

    /**
     * Unbounded and unconditional: a replay batch is a known, finite history and must never be
     * truncated by, or counted against, the live-delivery capacity bound below.
     */
    private void seedReplay(List<RunnerEvent> replayEvents) {
      for (RunnerEvent event : replayEvents) {
        mailbox.add(new Envelope(event, false));
      }
    }

    private void start() {
      executor.submit(this::deliverLoop);
    }

    /**
     * Live-publish path - bounded, independently of however large the replay backlog in the same
     * mailbox is. Disconnects this subscriber instead of growing its live backlog without limit if
     * it is falling behind.
     */
    private void offerLive(RunnerEvent event) {
      if (closed.get()) {
        return;
      }
      if (liveQueued.incrementAndGet() > LIVE_QUEUE_CAPACITY) {
        liveQueued.decrementAndGet(); // the rejected event was never enqueued - do not count it.
        close(
            new IllegalStateException(
                "Subscriber mailbox full for run " + runId + " - disconnecting"));
        return;
      }
      mailbox.add(new Envelope(event, true));
    }

    private void deliverLoop() {
      try {
        while (true) {
          Envelope envelope = mailbox.take();
          if (envelope.live()) {
            liveQueued.decrementAndGet();
          }
          try {
            subscriber.onEvent(envelope.event());
          } catch (Exception deliveryFailure) {
            close(deliveryFailure);
            return;
          }
          if (envelope.event().type() == EventType.RUN_FINISHED) {
            close(null);
            return;
          }
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void close() {
      close(null);
    }

    private void close(Throwable cause) {
      hub.lifecycleLock.readLock().lock();
      try {
        if (!closed.compareAndSet(false, true)) {
          return;
        }
        hub.unsubscribe(this);
        executor.shutdownNow();
        hub.beforeCloseNotify();
        hub.notifyTerminal(subscriber, cause);
      } finally {
        hub.lifecycleLock.readLock().unlock();
      }
    }

    private record Envelope(RunnerEvent event, boolean live) {}
  }
}
