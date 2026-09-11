package dev.vlaisanem.automation.runner.service.metrics;

import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService;
import dev.vlaisanem.automation.runner.service.exception.DiskUsageUnavailableException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Periodically samples {@link DiskUsageService#runnerDataBytes()} (a filesystem-tree walk) and
 * {@link DiskUsageService#databaseBytes()} (a live {@code pg_database_size} query) and caches the
 * result, since both are too expensive to recompute on every Prometheus scrape. Runs on its own
 * single-thread scheduler so a slow sample never delays retention or other scheduled work.
 *
 * <p>The two sources are sampled independently so a failure in one never blocks the other; a failed
 * sample keeps the stale cached value (never zeroed) and the paired {@code *_age_seconds} gauge
 * makes that staleness visible. Each gauge reports {@code NaN} until its source's first sample
 * completes.
 */
@Component
public class DiskMetricsSampler {

  private static final Logger log = LoggerFactory.getLogger(DiskMetricsSampler.class);

  private final DiskUsageService diskUsageService;
  private final RunnerMetrics metrics;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "disk-metrics-sampler");
            thread.setDaemon(true);
            return thread;
          });

  private final AtomicLong runnerDataBytes = new AtomicLong();
  private final AtomicLong databaseBytes = new AtomicLong();
  private final AtomicReference<Instant> lastRunnerDataSuccess = new AtomicReference<>();
  private final AtomicReference<Instant> lastDatabaseSuccess = new AtomicReference<>();

  public DiskMetricsSampler(
      RunnerProperties properties,
      DiskUsageService diskUsageService,
      RunnerMetrics metrics,
      MeterRegistry registry) {
    this.diskUsageService = diskUsageService;
    this.metrics = metrics;

    Gauge.builder("runner.disk.free_bytes", diskUsageService, DiskMetricsSampler::liveFreeBytes)
        .register(registry);
    Gauge.builder("runner.disk.runner_data_bytes", this, DiskMetricsSampler::runnerDataBytesOrNaN)
        .register(registry);
    Gauge.builder("runner.disk.database_bytes", this, DiskMetricsSampler::databaseBytesOrNaN)
        .register(registry);
    Gauge.builder(
            "runner.disk.runner_data_bytes.age_seconds",
            lastRunnerDataSuccess,
            DiskMetricsSampler::ageSeconds)
        .register(registry);
    Gauge.builder(
            "runner.disk.database_bytes.age_seconds",
            lastDatabaseSuccess,
            DiskMetricsSampler::ageSeconds)
        .register(registry);

    // RunnerProperties already rejects a sub-millisecond interval (toMillis() would truncate it
    // to 0, which scheduleWithFixedDelay rejects), so it's trusted here unchecked.
    long intervalMillis = properties.metricsSampleInterval().toMillis();
    scheduler.scheduleWithFixedDelay(this::sampleBoth, 0, intervalMillis, TimeUnit.MILLISECONDS);
  }

  @PreDestroy
  public void shutdown() {
    scheduler.shutdownNow();
    try {
      if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        log.warn(
            "disk-metrics-sampler thread did not terminate within 5s of shutdown - a filesystem"
                + " walk or database call may still be in flight");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * The single scheduled tick. The inner catches below already handle every {@code
   * RuntimeException}; this one is a defensive backstop only. Scoped to {@code RuntimeException},
   * not {@code Throwable}, so a genuine {@code Error} still propagates and stops rescheduling.
   */
  private void sampleBoth() {
    try {
      sampleRunnerData();
    } catch (RuntimeException e) {
      log.warn("Unexpected failure sampling runner-data bytes - will retry next tick", e);
    }
    try {
      sampleDatabase();
    } catch (RuntimeException e) {
      log.warn("Unexpected failure sampling database bytes - will retry next tick", e);
    }
  }

  private double runnerDataBytesOrNaN() {
    return lastRunnerDataSuccess.get() == null ? Double.NaN : (double) runnerDataBytes.get();
  }

  private double databaseBytesOrNaN() {
    return lastDatabaseSuccess.get() == null ? Double.NaN : (double) databaseBytes.get();
  }

  private void sampleRunnerData() {
    try {
      long bytes = diskUsageService.runnerDataBytes();
      runnerDataBytes.set(bytes);
      lastRunnerDataSuccess.set(Instant.now());
    } catch (RuntimeException e) {
      log.warn("Failed to sample runner-data bytes - keeping the last known value", e);
      metrics.recordSampleFailure(RunnerMetrics.SampleSource.RUNNER_DATA);
    }
  }

  private void sampleDatabase() {
    try {
      long bytes = diskUsageService.databaseBytes();
      databaseBytes.set(bytes);
      lastDatabaseSuccess.set(Instant.now());
    } catch (RuntimeException e) {
      log.warn("Failed to sample database bytes - keeping the last known value", e);
      metrics.recordSampleFailure(RunnerMetrics.SampleSource.DATABASE);
    }
  }

  /** Cheap (a syscall) - read live on every scrape, never cached, unlike the two sources above. */
  private static double liveFreeBytes(DiskUsageService diskUsageService) {
    try {
      return diskUsageService.snapshot().usableFreeBytes();
    } catch (DiskUsageUnavailableException e) {
      return Double.NaN;
    }
  }

  private static double ageSeconds(AtomicReference<Instant> lastSuccess) {
    Instant last = lastSuccess.get();
    if (last == null) {
      return Double.NaN;
    }
    return Duration.between(last, Instant.now()).getSeconds();
  }
}
