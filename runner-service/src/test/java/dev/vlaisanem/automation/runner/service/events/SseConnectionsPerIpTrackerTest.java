package dev.vlaisanem.automation.runner.service.events;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vlaisanem.automation.runner.service.config.RateLimitRule;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SseConnectionsPerIpTrackerTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private SseConnectionsPerIpTracker tracker(int sseMaxConnectionsPerIp) {
    return new SseConnectionsPerIpTracker(properties(sseMaxConnectionsPerIp), meterRegistry);
  }

  private double activeSlotsGaugeValue() {
    return meterRegistry.get("runner.sse.client_slots.active").gauge().value();
  }

  private static RunnerProperties properties(int sseMaxConnectionsPerIp) {
    RateLimitRule aRule = new RateLimitRule(5, Duration.ofMinutes(1));
    return new RunnerProperties(
        ".",
        Duration.ofMinutes(10),
        "build/runner-events/raw",
        "build/runner-logs",
        "src/test/resources/catalog/public-test-catalog.json",
        "build/runner-artifacts",
        1024 * 1024,
        Duration.ofSeconds(5),
        Duration.ofSeconds(2),
        5,
        Duration.ofMillis(150),
        Duration.ofSeconds(5),
        100,
        Duration.ofSeconds(15),
        Duration.ofMinutes(10),
        aRule,
        aRule,
        aRule,
        aRule,
        aRule,
        aRule,
        aRule,
        sseMaxConnectionsPerIp,
        16384,
        Duration.ofDays(30),
        500,
        Duration.ofDays(14),
        Duration.ofHours(1),
        aRule,
        1_048_576L,
        26_214_400L,
        209_715_200L,
        2_097_152L,
        2_097_152L,
        104_857_600L,
        aRule,
        Duration.ofSeconds(60));
  }

  @Test
  void allowsUpToTheConfiguredCapThenRejectsTheNextOne() {
    SseConnectionsPerIpTracker tracker = tracker(2);

    assertThat(tracker.tryAcquire("1.2.3.4")).isTrue();
    assertThat(tracker.tryAcquire("1.2.3.4")).isTrue();
    assertThat(tracker.tryAcquire("1.2.3.4")).isFalse();
  }

  @Test
  void releasingASlotAllowsAnotherAcquisition() {
    SseConnectionsPerIpTracker tracker = tracker(1);

    assertThat(tracker.tryAcquire("1.2.3.4")).isTrue();
    assertThat(tracker.tryAcquire("1.2.3.4")).isFalse();

    tracker.release("1.2.3.4");

    assertThat(tracker.tryAcquire("1.2.3.4")).isTrue();
  }

  @Test
  void tracksEachClientIpIndependently() {
    SseConnectionsPerIpTracker tracker = tracker(1);

    assertThat(tracker.tryAcquire("1.2.3.4")).isTrue();
    assertThat(tracker.tryAcquire("5.6.7.8")).isTrue();
    assertThat(tracker.tryAcquire("1.2.3.4")).isFalse();
    assertThat(tracker.tryAcquire("5.6.7.8")).isFalse();
  }

  @Test
  void releasingAnUnknownIpIsANoOp() {
    SseConnectionsPerIpTracker tracker = tracker(1);

    tracker.release("never-acquired");

    assertThat(tracker.tryAcquire("never-acquired")).isTrue();
  }

  /**
   * {@code runner.sse.client_slots.active} must track every real acquire/release across multiple
   * IPs, without double-counting a no-op release (unknown IP, or one already fully released) - this
   * is the metric that makes the CI SSE-cap flakiness observable.
   */
  @Test
  void theActiveSlotsGaugeTracksRealAcquiresAndReleasesAcrossMultipleIps() {
    SseConnectionsPerIpTracker tracker = tracker(2);
    assertThat(activeSlotsGaugeValue()).isZero();

    assertThat(tracker.tryAcquire("1.2.3.4")).isTrue();
    assertThat(activeSlotsGaugeValue()).isEqualTo(1);

    assertThat(tracker.tryAcquire("5.6.7.8")).isTrue();
    assertThat(activeSlotsGaugeValue()).isEqualTo(2);

    assertThat(tracker.tryAcquire("1.2.3.4")).isTrue();
    assertThat(activeSlotsGaugeValue()).isEqualTo(3);

    // A rejected acquire (over the cap) must never move the gauge.
    assertThat(tracker.tryAcquire("1.2.3.4")).isFalse();
    assertThat(activeSlotsGaugeValue()).isEqualTo(3);

    // A no-op release (nothing acquired for this IP) must never move the gauge either.
    tracker.release("never-acquired");
    assertThat(activeSlotsGaugeValue()).isEqualTo(3);

    tracker.release("1.2.3.4");
    assertThat(activeSlotsGaugeValue()).isEqualTo(2);

    tracker.release("1.2.3.4");
    tracker.release("5.6.7.8");
    assertThat(activeSlotsGaugeValue()).isZero();
  }
}
