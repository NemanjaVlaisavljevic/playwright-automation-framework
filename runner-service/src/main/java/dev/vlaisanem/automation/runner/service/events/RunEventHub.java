package dev.vlaisanem.automation.runner.service.events;

import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.exception.RunEventSubscriptionRejectedException;
import dev.vlaisanem.automation.runner.service.metrics.RunnerMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
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
 * Fans a run's canonical events out to zero or more live subscribers. {@link #publish} only
 * enqueues into each subscriber's mailbox (fast, non-blocking); actual delivery happens on that
 * subscriber's own dedicated thread, decoupled from the publisher.
 *
 * <p>No backpressure on the live path: a subscriber that can't keep up is disconnected outright
 * rather than blocking the publisher or growing its mailbox unbounded. The replay batch a
 * subscriber is seeded with is a separate, unbounded concern - a known, finite history never
 * counted against live capacity. Expected recovery from a live disconnect is a client reconnect
 * with {@code Last-Event-ID}, not a slow-consumer protocol.
 *
 * <p>A subscription closes itself once it delivers a {@link EventType#RUN_FINISHED} event, since
 * nothing follows it in a run's canonical timeline. A subscriber's terminal callback always runs
 * off whatever thread triggered the close (see {@link #notifyTerminal}), and swallows any exception
 * it throws - critical for the live-overflow disconnect, detected on the publisher thread itself,
 * which must never block or throw back into {@link RunEventBroker#append}.
 *
 * <p>{@link #subscribe} enforces a hard cap ({@code maxSubscribers}), since every subscriber holds
 * its own dedicated delivery thread for the life of its connection. {@link #shutdown} closes every
 * active subscription and stops accepting new ones.
 *
 * <p>{@code lifecycleLock} makes shutdown's "close everything and tear down the executor" atomic
 * against concurrent {@code subscribe}/{@code close} calls: {@code subscribe}/{@code close} take
 * the shared read lock (so they proceed in parallel against each other) while {@code shutdown}
 * takes the exclusive write lock for its whole body. This guarantees a subscription created
 * concurrently with shutdown is provably either rejected or included in its snapshot, and that
 * {@code close} can never straddle shutdown's executor teardown and throw a {@link
 * RejectedExecutionException} back through a publisher thread; {@code close}'s idempotency check
 * must sit inside the locked section for the same reason.
 *
 * <p>Package-private: {@link #subscribe} is only called by {@link RunEventBroker}, which holds the
 * per-run lock needed to combine it atomically with a replay snapshot - calling it directly could
 * let a live event slip into a subscriber's mailbox interleaved with its own replay batch.
 */
class RunEventHub {

  private static final int LIVE_QUEUE_CAPACITY = 256;

  private final int maxSubscribers;
  private final RunnerMetrics metrics;
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

  RunEventHub(int maxSubscribers, RunnerMetrics metrics, MeterRegistry meterRegistry) {
    this.maxSubscribers = maxSubscribers;
    this.metrics = metrics;
    Gauge.builder("runner.sse.connections.active", subscriberCount, AtomicInteger::get)
        .register(meterRegistry);
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
        metrics.recordSseRejection(RunnerMetrics.SseRejectionReason.GLOBAL_CAP);
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
   * Stops accepting new subscriptions and closes every currently active one, each still getting its
   * normal terminal callback. Holds the exclusive write lock for the whole snapshot +
   * close-everything + executor shutdown sequence (see the class Javadoc); the bounded {@code
   * awaitTermination} wait runs after releasing the lock, once nothing can race it any further.
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
   * Runs one subscriber's terminal callback on a dedicated notifier thread, never the thread that
   * triggered the close, and swallows any exception it throws. Without this, a live-overflow
   * disconnect detected on the publisher thread could let a blocking or throwing callback stall or
   * fail {@link RunEventBroker#append} after its event was already durably written to the journal.
   * The {@link RejectedExecutionException} catch is a last-resort safety net alongside {@code
   * lifecycleLock}, which is what actually prevents a live {@code close()} from reaching a
   * torn-down executor.
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
   * Test seam only - does nothing in production. Overridden in tests to deterministically reproduce
   * {@code subscribe()} running concurrently, mid-registration, with {@code shutdown()}'s snapshot.
   */
  void beforeSubscribeRegistration() {}

  /**
   * Test seam only - does nothing in production. Overridden in tests to deterministically reproduce
   * {@link Subscription#close} running concurrently, mid-notify, with {@code shutdown()}'s executor
   * teardown.
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
     * seeded at construction, which must stay unbounded.
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
     * Unbounded and unconditional: never truncated by, or counted against, the live-delivery cap.
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
     * Live-publish path - bounded independently of the replay backlog in the same mailbox.
     * Disconnects the subscriber instead of growing its live backlog without limit.
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
