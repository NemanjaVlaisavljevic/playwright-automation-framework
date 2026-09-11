package dev.vlaisanem.automation.runner.service.events;

import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * A per-client-IP concurrent-connection cap for SSE subscriptions, enforced alongside - never
 * instead of - {@link RunEventHub}'s own {@code sseMaxSubscribers} global ceiling: the hub's cap
 * protects the server's total thread/connection budget, this one protects fairness across clients.
 *
 * <p>A live count, not a time-windowed rate - {@link #tryAcquire} must be paired with exactly one
 * later {@link #release} call once that connection actually ends.
 *
 * <p>{@code runner.sse.client_slots.active} exposes the same total this class enforces against,
 * deliberately without a {@code clientIp} tag (unbounded cardinality), watched independently of
 * {@link RunEventHub}'s {@code runner.sse.connections.active}: the two can legitimately disagree
 * for a real window (this one nonzero while the hub's count has already dropped to zero), which is
 * the gap that lets a stale per-IP slot outlive its subscription and starve the next connection.
 */
@Component
public class SseConnectionsPerIpTracker {

  private final int maxConnectionsPerIp;
  private final ConcurrentHashMap<String, Integer> activeCounts = new ConcurrentHashMap<>();
  private final AtomicInteger totalActiveSlots = new AtomicInteger();

  public SseConnectionsPerIpTracker(RunnerProperties properties, MeterRegistry meterRegistry) {
    this.maxConnectionsPerIp = properties.sseMaxConnectionsPerIp();
    Gauge.builder("runner.sse.client_slots.active", totalActiveSlots, AtomicInteger::get)
        .register(meterRegistry);
  }

  /**
   * @return {@code true} if this IP was under its cap and is now counted one higher.
   */
  public boolean tryAcquire(String clientIp) {
    boolean[] acquired = {false};
    activeCounts.compute(
        clientIp,
        (ignored, current) -> {
          int existing = current == null ? 0 : current;
          if (existing >= maxConnectionsPerIp) {
            acquired[0] = false;
            return current;
          }
          acquired[0] = true;
          return existing + 1;
        });
    if (acquired[0]) {
      totalActiveSlots.incrementAndGet();
    }
    return acquired[0];
  }

  /** Releases one previously-{@link #tryAcquire}d slot for this IP - removes the entry at zero. */
  public void release(String clientIp) {
    boolean[] released = {false};
    activeCounts.computeIfPresent(
        clientIp,
        (ignored, current) -> {
          released[0] = true;
          return current <= 1 ? null : current - 1;
        });
    if (released[0]) {
      totalActiveSlots.decrementAndGet();
    }
  }
}
