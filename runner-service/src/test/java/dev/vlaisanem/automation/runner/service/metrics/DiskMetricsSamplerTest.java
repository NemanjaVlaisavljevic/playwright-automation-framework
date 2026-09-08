package dev.vlaisanem.automation.runner.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vlaisanem.automation.runner.service.config.RateLimitRule;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService.DiskUsageSnapshot;
import dev.vlaisanem.automation.runner.service.exception.DiskUsageUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * D4.3.2 - proves the sampler's own review-mandated behaviors directly: an immediate first sample
 * (no waiting a full interval), each source sampled independently (one failing must never prevent
 * the other from refreshing), and a failed sample leaves the previous cached value in place while
 * still counting the failure.
 */
class DiskMetricsSamplerTest {

  @Test
  void takesTheFirstSampleImmediatelyRatherThanWaitingAFullInterval() throws Exception {
    DiskUsageService diskUsageService = mock(DiskUsageService.class);
    when(diskUsageService.runnerDataBytes()).thenReturn(123L);
    when(diskUsageService.databaseBytes()).thenReturn(456L);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RunnerMetrics metrics = new RunnerMetrics(registry);

    DiskMetricsSampler sampler =
        new DiskMetricsSampler(
            propertiesWithSampleInterval(Duration.ofHours(1)), diskUsageService, metrics, registry);
    try {
      awaitTrue(() -> registry.find("runner.disk.runner_data_bytes").gauge().value() == 123.0);
      awaitTrue(() -> registry.find("runner.disk.database_bytes").gauge().value() == 456.0);
    } finally {
      sampler.shutdown();
    }
  }

  @Test
  void oneSourceFailingNeverPreventsTheOtherFromRefreshing() throws Exception {
    DiskUsageService diskUsageService = mock(DiskUsageService.class);
    when(diskUsageService.runnerDataBytes()).thenThrow(new RuntimeException("simulated failure"));
    when(diskUsageService.databaseBytes()).thenReturn(999L);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RunnerMetrics metrics = new RunnerMetrics(registry);

    DiskMetricsSampler sampler =
        new DiskMetricsSampler(
            propertiesWithSampleInterval(Duration.ofHours(1)), diskUsageService, metrics, registry);
    try {
      awaitTrue(() -> registry.find("runner.disk.database_bytes").gauge().value() == 999.0);
      awaitTrue(
          () ->
              registry
                      .find("runner.metrics.sampler.failures")
                      .tag("source", "RUNNER_DATA")
                      .counter()
                  != null);
      assertThat(registry.find("runner.disk.runner_data_bytes").gauge().value())
          .as("a source that has never once succeeded reports NaN, never a misleading 0")
          .isNaN();
    } finally {
      sampler.shutdown();
    }
  }

  /**
   * D4.3.2 review finding - the gauge is registered synchronously in the constructor, but the first
   * sample it reports is only scheduled there, not completed - the two are not atomic, so a scrape
   * landing in that real window must never see a misleadingly-real-looking {@code 0}.
   * Deterministic, not timing-based: blocks the first sample on a latch, reads the gauge while it
   * is still blocked (must be {@code NaN}), then releases it and reads again (must be the real
   * value).
   */
  @Test
  void gaugeReportsNaNUntilTheFirstSampleActuallyCompletes() throws Exception {
    DiskUsageService diskUsageService = mock(DiskUsageService.class);
    CountDownLatch releaseFirstSample = new CountDownLatch(1);
    CountDownLatch firstSampleEntered = new CountDownLatch(1);
    when(diskUsageService.runnerDataBytes())
        .thenAnswer(
            invocation -> {
              firstSampleEntered.countDown();
              releaseFirstSample.await();
              return 777L;
            });
    when(diskUsageService.databaseBytes()).thenReturn(1L);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RunnerMetrics metrics = new RunnerMetrics(registry);

    DiskMetricsSampler sampler =
        new DiskMetricsSampler(
            propertiesWithSampleInterval(Duration.ofHours(1)), diskUsageService, metrics, registry);
    try {
      assertThat(firstSampleEntered.await(5, TimeUnit.SECONDS))
          .as("the first sample must actually have started by now")
          .isTrue();
      assertThat(registry.find("runner.disk.runner_data_bytes").gauge().value())
          .as("still mid-flight - must not yet report the eventual real value, or 0")
          .isNaN();

      releaseFirstSample.countDown();

      awaitTrue(() -> registry.find("runner.disk.runner_data_bytes").gauge().value() == 777.0);
    } finally {
      sampler.shutdown();
    }
  }

  @Test
  void liveFreeBytesGaugeReadsSnapshotDirectlyAndReturnsNaNWhenTheProbeItselfFails() {
    DiskUsageService diskUsageService = mock(DiskUsageService.class);
    when(diskUsageService.runnerDataBytes()).thenReturn(1L);
    when(diskUsageService.databaseBytes()).thenReturn(1L);
    when(diskUsageService.snapshot())
        .thenReturn(new DiskUsageSnapshot(555L, 1_048_576L, 314_572_800L, Instant.now()));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RunnerMetrics metrics = new RunnerMetrics(registry);

    DiskMetricsSampler sampler =
        new DiskMetricsSampler(
            propertiesWithSampleInterval(Duration.ofHours(1)), diskUsageService, metrics, registry);
    // The free_bytes gauge reads diskUsageService.snapshot() live on every .value() call,
    // entirely independent of the periodic sampler thread - shut that thread down immediately so
    // its own concurrent calls to runnerDataBytes()/databaseBytes() on this same mock can never
    // race this test's own re-stubbing of snapshot() (Mockito stubbing is not thread-safe against
    // concurrent invocations on the same mock).
    sampler.shutdown();

    assertThat(registry.find("runner.disk.free_bytes").gauge().value()).isEqualTo(555.0);

    when(diskUsageService.snapshot())
        .thenThrow(new DiskUsageUnavailableException("simulated probe failure", null));
    assertThat(registry.find("runner.disk.free_bytes").gauge().value()).isNaN();
  }

  private static void awaitTrue(BooleanSupplier condition) throws Exception {
    Instant deadline = Instant.now().plusSeconds(5);
    while (Instant.now().isBefore(deadline)) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Condition never became true within 5 seconds");
  }

  private static RunnerProperties propertiesWithSampleInterval(Duration metricsSampleInterval) {
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
        3,
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
        metricsSampleInterval);
  }
}
