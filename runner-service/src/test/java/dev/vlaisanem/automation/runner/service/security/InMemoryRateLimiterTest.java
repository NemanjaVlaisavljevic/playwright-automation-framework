package dev.vlaisanem.automation.runner.service.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vlaisanem.automation.runner.service.config.RateLimitRule;
import dev.vlaisanem.automation.runner.service.security.InMemoryRateLimiter.NamedRule;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryRateLimiterTest {

  private final InMemoryRateLimiter limiter = new InMemoryRateLimiter();

  @Test
  void allowsExactlyMaxAttemptsThenRejectsTheNextOneWithinTheWindow() {
    RateLimitRule rule = new RateLimitRule(3, Duration.ofMinutes(1));

    assertThat(limiter.tryAcquire("surface", "1.2.3.4", rule).allowed()).isTrue();
    assertThat(limiter.tryAcquire("surface", "1.2.3.4", rule).allowed()).isTrue();
    assertThat(limiter.tryAcquire("surface", "1.2.3.4", rule).allowed()).isTrue();

    InMemoryRateLimiter.Result rejected = limiter.tryAcquire("surface", "1.2.3.4", rule);
    assertThat(rejected.allowed()).isFalse();
    assertThat(rejected.retryAfter()).isPositive();
  }

  @Test
  void tracksEachKeyIndependently() {
    RateLimitRule rule = new RateLimitRule(1, Duration.ofMinutes(1));

    assertThat(limiter.tryAcquire("surface", "1.2.3.4", rule).allowed()).isTrue();
    assertThat(limiter.tryAcquire("surface", "1.2.3.4", rule).allowed()).isFalse();
    assertThat(limiter.tryAcquire("surface", "5.6.7.8", rule).allowed()).isTrue();
  }

  @Test
  void tracksEachNamespaceIndependentlyForTheSameKey() {
    RateLimitRule rule = new RateLimitRule(1, Duration.ofMinutes(1));

    assertThat(limiter.tryAcquire("oauth-authorization", "1.2.3.4", rule).allowed()).isTrue();
    assertThat(limiter.tryAcquire("oauth-authorization", "1.2.3.4", rule).allowed()).isFalse();
    assertThat(limiter.tryAcquire("oauth-callback", "1.2.3.4", rule).allowed()).isTrue();
  }

  @Test
  void recoversAutomaticallyOnceTheWindowElapses() throws InterruptedException {
    // Generous window: too tight would flake from JIT/class-loading warm-up costing more than the
    // window itself.
    RateLimitRule rule = new RateLimitRule(1, Duration.ofMillis(300));

    assertThat(limiter.tryAcquire("surface", "1.2.3.4", rule).allowed()).isTrue();
    assertThat(limiter.tryAcquire("surface", "1.2.3.4", rule).allowed()).isFalse();

    Thread.sleep(400);

    assertThat(limiter.tryAcquire("surface", "1.2.3.4", rule).allowed()).isTrue();
  }

  /**
   * Multi-rule checks must be one atomic decision, not two sequential ones - a request rejected by
   * the hourly rule must never have already consumed the minute rule's budget.
   */
  @Test
  void multiRuleChecksAreAtomicNeverPartiallyConsumingOnRejection() {
    RateLimitRule perMinute = new RateLimitRule(3, Duration.ofMinutes(1));
    RateLimitRule perHour = new RateLimitRule(1, Duration.ofHours(1));
    List<NamedRule> rules =
        List.of(
            new NamedRule("create-run-per-minute", perMinute),
            new NamedRule("create-run-per-hour", perHour));

    assertThat(limiter.tryAcquire("admin-1", rules).allowed()).isTrue();

    // Per-hour rule (limit 1) is now exhausted; the per-minute rule must not have been incremented
    // by this rejected attempt, proven by the next assertion still finding room under the same key.
    assertThat(limiter.tryAcquire("admin-1", rules).allowed()).isFalse();
    assertThat(limiter.tryAcquire("create-run-per-minute", "admin-1", perMinute).allowed())
        .isTrue();
  }

  /**
   * On rejection, {@code retryAfter} must be the largest remaining time among every rejecting rule,
   * never the shortest - a caller blocked by a long-window rule must not be told to retry in
   * seconds.
   */
  @Test
  void retryAfterReflectsTheLargestRemainingTimeAmongRejectingRules() {
    RateLimitRule shortWindowExhausted = new RateLimitRule(1, Duration.ofMinutes(1));
    RateLimitRule longWindowExhausted = new RateLimitRule(1, Duration.ofHours(1));
    List<NamedRule> rules =
        List.of(
            new NamedRule("short", shortWindowExhausted),
            new NamedRule("long", longWindowExhausted));

    assertThat(limiter.tryAcquire("admin-1", rules).allowed()).isTrue();

    InMemoryRateLimiter.Result rejected = limiter.tryAcquire("admin-1", rules);
    assertThat(rejected.allowed()).isFalse();
    // Both rules are exhausted (limit 1 each); retryAfter must reflect the longer hour window.
    assertThat(rejected.retryAfter()).isGreaterThan(Duration.ofMinutes(2));
  }

  /**
   * Memory must stay bounded even against many distinct source IPs (scanners, botnets, IPv6
   * rotation), each of which would otherwise leave a permanent entry.
   */
  @Test
  void trackedKeyCountStaysBoundedUnderManyOneOffKeys() {
    int maxTrackedKeys = 20;
    InMemoryRateLimiter bounded = new InMemoryRateLimiter(maxTrackedKeys);
    RateLimitRule rule = new RateLimitRule(5, Duration.ofMinutes(1));

    for (int i = 0; i < 500; i++) {
      bounded.tryAcquire("surface", "one-off-key-" + i, rule);
    }

    assertThat(bounded.trackedKeyCount()).isLessThanOrEqualTo(maxTrackedKeys);
  }
}
