package dev.vlaisanem.automation.runner.service.events;

import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * A per-client-IP concurrent-connection cap for SSE subscriptions (D3.3), enforced
 * <em>alongside</em> - never instead of - {@link RunEventHub}'s own {@code sseMaxSubscribers}
 * global ceiling. Without this, one client alone could occupy every global slot, starving every
 * other viewer; the hub's own cap protects the server's total thread/connection budget, this one
 * protects fairness across clients.
 *
 * <p>A live count, not a time-windowed rate - {@link #tryAcquire} must be paired with exactly one
 * later {@link #release} call once that specific connection actually ends (see {@code
 * RunEventStreamController#stream}, which composes this into the same {@code onClose} callback
 * already used to cancel that connection's heartbeat).
 */
@Component
public class SseConnectionsPerIpTracker {

  private final int maxConnectionsPerIp;
  private final ConcurrentHashMap<String, Integer> activeCounts = new ConcurrentHashMap<>();

  public SseConnectionsPerIpTracker(RunnerProperties properties) {
    this.maxConnectionsPerIp = properties.sseMaxConnectionsPerIp();
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
    return acquired[0];
  }

  /** Releases one previously-{@link #tryAcquire}d slot for this IP - removes the entry at zero. */
  public void release(String clientIp) {
    activeCounts.computeIfPresent(
        clientIp, (ignored, current) -> current <= 1 ? null : current - 1);
  }
}
