package dev.vlaisanem.automation.runner.service.events;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vlaisanem.automation.runner.service.config.RateLimitRule;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SseConnectionsPerIpTrackerTest {

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
        16384);
  }

  @Test
  void allowsUpToTheConfiguredCapThenRejectsTheNextOne() {
    SseConnectionsPerIpTracker tracker = new SseConnectionsPerIpTracker(properties(2));

    assertThat(tracker.tryAcquire("1.2.3.4")).isTrue();
    assertThat(tracker.tryAcquire("1.2.3.4")).isTrue();
    assertThat(tracker.tryAcquire("1.2.3.4")).isFalse();
  }

  @Test
  void releasingASlotAllowsAnotherAcquisition() {
    SseConnectionsPerIpTracker tracker = new SseConnectionsPerIpTracker(properties(1));

    assertThat(tracker.tryAcquire("1.2.3.4")).isTrue();
    assertThat(tracker.tryAcquire("1.2.3.4")).isFalse();

    tracker.release("1.2.3.4");

    assertThat(tracker.tryAcquire("1.2.3.4")).isTrue();
  }

  @Test
  void tracksEachClientIpIndependently() {
    SseConnectionsPerIpTracker tracker = new SseConnectionsPerIpTracker(properties(1));

    assertThat(tracker.tryAcquire("1.2.3.4")).isTrue();
    assertThat(tracker.tryAcquire("5.6.7.8")).isTrue();
    assertThat(tracker.tryAcquire("1.2.3.4")).isFalse();
    assertThat(tracker.tryAcquire("5.6.7.8")).isFalse();
  }

  @Test
  void releasingAnUnknownIpIsANoOp() {
    SseConnectionsPerIpTracker tracker = new SseConnectionsPerIpTracker(properties(1));

    tracker.release("never-acquired");

    assertThat(tracker.tryAcquire("never-acquired")).isTrue();
  }
}
