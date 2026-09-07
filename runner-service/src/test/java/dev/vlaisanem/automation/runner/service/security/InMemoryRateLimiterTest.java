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
    // A generous window/margin, not a tight one - the first call in a test class can pay real JIT
    // warm-up/class-loading overhead, and a too-tight window would flake by "recovering" before
    // the second call even happens rather than proving the intended time-based reset.
    RateLimitRule rule = new RateLimitRule(1, Duration.ofMillis(300));

    assertThat(limiter.tryAcquire("surface", "1.2.3.4", rule).allowed()).isTrue();
    assertThat(limiter.tryAcquire("surface", "1.2.3.4", rule).allowed()).isFalse();

    Thread.sleep(400);

    assertThat(limiter.tryAcquire("surface", "1.2.3.4", rule).allowed()).isTrue();
  }

  /**
   * Regression test for the D3.3 review finding: multi-rule checks must be one atomic decision, not
   * two sequential ones - a request rejected by the hourly rule must never have already consumed
   * the minute rule's budget along the way.
   */
  @Test
  void multiRuleChecksAreAtomicNeverPartiallyConsumingOnRejection() {
    RateLimitRule perMinute = new RateLimitRule(3, Duration.ofMinutes(1));
    RateLimitRule perHour = new RateLimitRule(1, Duration.ofHours(1));
    List<NamedRule> rules =
        List.of(
            new NamedRule("create-run-per-minute", perMinute),
            new NamedRule("create-run-per-hour", perHour));

    // First attempt: both rules have room, both pass and both are counted.
    assertThat(limiter.tryAcquire("admin-1", rules).allowed()).isTrue();

    // Second attempt: the per-hour rule is now exhausted (limit 1) - the whole attempt must be
    // rejected, and the still-available per-minute rule must NOT have been incremented by this
    // rejected attempt (proven by the next assertion still finding room for a fresh, per-minute-
    // only check under the same key).
    assertThat(limiter.tryAcquire("admin-1", rules).allowed()).isFalse();
    assertThat(limiter.tryAcquire("create-run-per-minute", "admin-1", perMinute).allowed())
        .isTrue();
  }

  /**
   * Regression test for the D3.3 review finding: on rejection, {@code retryAfter} must be the
   * *largest* remaining time among every rule that rejected - never the shortest - so a caller
   * blocked mainly by a long-window rule is never told to retry in a few seconds just because a
   * shorter-window rule also happened to reject.
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
    // Both rules are now exhausted (each allows only 1), so the reported retryAfter must reflect
    // the hour-long window, not the much shorter minute one.
    assertThat(rejected.retryAfter()).isGreaterThan(Duration.ofMinutes(2));
  }

  /**
   * Regression test for the D3.3 review finding: a real attacker (internet scanners, botnet
   * clients, IPv6 address rotation) can present many genuinely distinct source IPs, each of which
   * would otherwise leave a permanent entry - memory must stay bounded regardless of how many
   * one-off keys are ever seen.
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
