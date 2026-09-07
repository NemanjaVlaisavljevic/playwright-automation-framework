package dev.vlaisanem.automation.runner.service.config;

import java.time.Duration;

/**
 * One fixed-window rate-limit threshold - at most {@code maxAttempts} within {@code window},
 * counted per key (client IP or authenticated GitHub numeric id, depending on the surface - see
 * {@code security.AbuseRateLimitFilter}). A surface needing more than one simultaneous window (e.g.
 * create-run's per-minute *and* per-hour caps) simply gets two separate {@code RateLimitRule}
 * fields on {@link RunnerProperties}, each checked independently.
 *
 * @param maxAttempts maximum number of attempts allowed within {@link #window}.
 * @param window the fixed window duration attempts are counted over.
 */
public record RateLimitRule(int maxAttempts, Duration window) {

  public RateLimitRule {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("max-attempts must be at least 1");
    }
    if (window == null || window.isZero() || window.isNegative()) {
      throw new IllegalArgumentException("window must be positive");
    }
  }
}
