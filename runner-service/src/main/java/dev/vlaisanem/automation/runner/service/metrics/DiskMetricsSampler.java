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
 * D4.3.2 - {@link DiskUsageService#runnerDataBytes()} (a filesystem-tree walk) and {@link
 * DiskUsageService#databaseBytes()} (a live {@code pg_database_size} query) are both real work -
 * too expensive to recompute on every Prometheus scrape, which could come as often as every few
 * seconds. This sampler recomputes both on a fixed background delay instead and caches the result;
 * the two gauges below only ever read that cache, never recompute live.
 *
 * <p>Runs on its own dedicated single-thread {@link ScheduledExecutorService} - never the one
 * {@code RetentionScheduler} or anything else uses - so a slow filesystem walk can never delay
 * retention sweeps or any other scheduled work. Uses {@code scheduleWithFixedDelay}, not a fixed
 * rate, so one slow sample can never overlap the next. The first sample runs immediately ({@code
 * initialDelay = 0}), so the gauges never show a misleadingly-fresh {@code 0} for the first {@link
 * RunnerProperties#metricsSampleInterval()} after startup.
 *
 * <p>The two sources are sampled independently, each in its own try/catch - a Postgres hiccup must
 * never prevent the disk-side figure from refreshing, and vice versa. A failed sample leaves the
 * previous cached value in place (stale-but-available, never wiped to {@code 0}) and increments
 * {@link RunnerMetrics#recordSampleFailure}; the paired {@code *_age_seconds} gauge is what makes
 * that staleness visible, since the value itself would otherwise look permanently fresh. The outer
 * scheduled task's own catch is deliberately scoped to {@link RuntimeException}, not {@link
 * Throwable} (a review finding): {@code sampleRunnerData}/{@code sampleDatabase} already handle
 * every {@code RuntimeException} internally, so nothing should ever reach this outer catch in
 * practice - it exists only as a defensive backstop against a future refactor accidentally dropping
 * one of those inner catches. A genuine {@code Error} (an {@code OutOfMemoryError}, a {@code
 * StackOverflowError}, a {@code LinkageError}) is a real JVM-level failure this sampler has no
 * business hiding - swallowing it here would leave the process looking alive while some other part
 * of the JVM may already be in a corrupted state, exactly the kind of silent, misleading survival
 * this project's other fail-loud paths (e.g. {@code RunRecoveryService}'s own startup gate) are
 * built to avoid. Letting it propagate out of this scheduled task is correct: {@link
 * ScheduledExecutorService} stops scheduling further executions of a task that threw, which for a
 * genuine {@code Error} is the right outcome, not a bug to work around.
 *
 * <p>The two disk-size gauges themselves report {@code NaN} until each source's own first sample
 * has actually completed (gated on the same {@code lastSuccess} reference the age gauge already
 * tracks), never a misleadingly-real-looking {@code 0} for whatever window exists between this
 * constructor scheduling that first sample and it actually finishing on the background thread - the
 * two events are not atomic with each other, and a scrape can land in between.
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

    // RunnerProperties's own compact constructor already rejects a sub-millisecond interval (a
    // D4.3.2 review finding: a merely-positive Duration can still truncate to 0 via toMillis(),
    // which scheduleWithFixedDelay itself would otherwise reject) - trusted here unchecked, the
    // same way every other RunnerProperties-derived value already is throughout this codebase.
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
   * The single scheduled tick. {@code sampleRunnerData}/{@code sampleDatabase} already handle every
   * {@code RuntimeException} internally, so this catch is a defensive backstop only - see this
   * class's own Javadoc for why it is deliberately scoped to {@code RuntimeException}, not {@code
   * Throwable}: a genuine {@code Error} must propagate, not be swallowed here.
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
