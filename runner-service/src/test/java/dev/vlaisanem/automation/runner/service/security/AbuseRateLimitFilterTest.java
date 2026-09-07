package dev.vlaisanem.automation.runner.service.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the D3.3 review finding: {@code Duration.toSeconds()} truncates, so 59.9
 * remaining seconds would floor to {@code Retry-After: 59} - advising a client to retry slightly
 * before the window has actually elapsed. {@link AbuseRateLimitFilter#ceilSecondsAtLeastOne} must
 * round up instead.
 */
class AbuseRateLimitFilterTest {

  @Test
  void roundsAWholeNumberOfSecondsUnchanged() {
    assertThat(AbuseRateLimitFilter.ceilSecondsAtLeastOne(Duration.ofSeconds(60))).isEqualTo(60);
  }

  @Test
  void roundsUpAnyFractionalRemainder() {
    assertThat(AbuseRateLimitFilter.ceilSecondsAtLeastOne(Duration.ofMillis(59_900))).isEqualTo(60);
    assertThat(AbuseRateLimitFilter.ceilSecondsAtLeastOne(Duration.ofMillis(1))).isEqualTo(1);
  }

  @Test
  void neverReturnsLessThanOneSecondEvenForZero() {
    assertThat(AbuseRateLimitFilter.ceilSecondsAtLeastOne(Duration.ZERO)).isEqualTo(1);
  }
}
