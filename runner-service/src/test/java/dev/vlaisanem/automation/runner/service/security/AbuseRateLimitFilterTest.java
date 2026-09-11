package dev.vlaisanem.automation.runner.service.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link AbuseRateLimitFilter#ceilSecondsAtLeastOne} must round up: {@code Duration.toSeconds()}
 * truncates, which would floor 59.9 remaining seconds to {@code Retry-After: 59} and advise a retry
 * before the window has actually elapsed.
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
