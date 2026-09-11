package dev.vlaisanem.automation.runner.service.metrics;

import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.retention.RetentionReport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The one place every domain counter/timer this service exposes is recorded, so cardinality and
 * failure semantics are enforced in one place. Gauges are registered directly by the class that
 * owns the value ({@code RunService}, {@code RunEventHub}, {@code DiskMetricsSampler}) instead.
 *
 * <p>Every {@code record*} method swallows its own Micrometer/registry failures (logged, not
 * thrown) so a metrics-backend problem never affects a run's outcome; precondition checks still
 * throw normally. Every tag is an enum, never a free-form {@code String}, so no method can be
 * called with a {@code runId} or other unbounded value as a tag. Counters reset to zero on every
 * restart - there's no Prometheus server yet to retain history across one.
 */
@Component
public class RunnerMetrics {

  private static final Logger log = LoggerFactory.getLogger(RunnerMetrics.class);

  /** The fixed, low-cardinality reason set for a rejected disk-guarded submit. */
  public enum DiskRejectionPhase {
    SUBMIT,
    PRE_LAUNCH
  }

  /** The fixed, low-cardinality reason set for a rejected SSE subscription. */
  public enum SseRejectionReason {
    GLOBAL_CAP,
    PER_IP_CAP
  }

  /** Which of {@code DiskMetricsSampler}'s two independently-sampled sources failed. */
  public enum SampleSource {
    RUNNER_DATA,
    DATABASE
  }

  private final MeterRegistry registry;

  public RunnerMetrics(MeterRegistry registry) {
    this.registry = registry;
  }

  /** A run was durably queued - always called, since {@code queue} never contends. */
  public void recordRunSubmitted(Suite suite) {
    safely(
        () ->
            Counter.builder("runner.runs.submitted")
                .tag("suite", suite.name())
                .register(registry)
                .increment());
  }

  /** A run actually started - only ever called once its transition genuinely applied. */
  public void recordRunStarted(Suite suite) {
    safely(
        () ->
            Counter.builder("runner.runs.started")
                .tag("suite", suite.name())
                .register(registry)
                .increment());
  }

  /**
   * A run reached a terminal status - only ever called once its transition genuinely applied, never
   * speculatively and never more than once for the same transition.
   *
   * @param startedAt the committed run's own {@code startedAt}; {@code null} when the run was
   *     cancelled/errored before ever reaching {@code RUNNING} (a real, valid case - {@code Run}'s
   *     own compact constructor permits it) - duration is recorded only when this is non-null.
   * @param finishedAt the committed run's own {@code finishedAt} - required whenever {@code
   *     startedAt} is non-null.
   */
  public void recordRunFinished(
      Suite suite, RunStatus status, Instant startedAt, Instant finishedAt) {
    if (!status.isTerminal()) {
      throw new IllegalArgumentException(
          "recordRunFinished requires a terminal status, was: " + status);
    }
    safely(
        () ->
            Counter.builder("runner.runs.finished")
                .tag("suite", suite.name())
                .tag("status", status.name())
                .register(registry)
                .increment());
    if (startedAt != null && finishedAt != null) {
      Duration duration = Duration.between(startedAt, finishedAt);
      safely(
          () ->
              Timer.builder("runner.runs.duration")
                  .tag("suite", suite.name())
                  .tag("status", status.name())
                  .register(registry)
                  .record(duration));
    }
  }

  public void recordDiskRejection(DiskRejectionPhase phase) {
    safely(
        () ->
            Counter.builder("runner.runs.disk_rejections")
                .tag("phase", phase.name())
                .register(registry)
                .increment());
  }

  public void recordSseRejection(SseRejectionReason reason) {
    safely(
        () ->
            Counter.builder("runner.sse.connections.rejected")
                .tag("reason", reason.name())
                .register(registry)
                .increment());
  }

  /** Recorded exactly once at startup, from {@code RunRecoveryService}'s own computed count. */
  public void recordRunsRecovered(int recoveredCount) {
    if (recoveredCount <= 0) {
      return;
    }
    safely(
        () ->
            Counter.builder("runner.recovery.recovered")
                .register(registry)
                .increment(recoveredCount));
  }

  /**
   * Recorded once per real (non-dry-run, non-skipped) retention sweep, whichever of {@code
   * RetentionScheduler}'s own tick or {@code RetentionController}'s manual trigger produced it -
   * see {@code RetentionService#sweep} for where this is called, always from the one place both
   * converge.
   */
  public void recordRetention(RetentionReport report) {
    safely(
        () ->
            Counter.builder("runner.retention.runs_deleted")
                .register(registry)
                .increment(report.runDeletedCount()));
    safely(
        () ->
            Counter.builder("runner.retention.bytes_freed")
                .register(registry)
                .increment(report.bytesFreed()));
    int itemFailures = report.runFailedCount() + report.artifactPurgeFailedCount();
    if (itemFailures > 0) {
      safely(
          () ->
              Counter.builder("runner.retention.item_failures")
                  .register(registry)
                  .increment(itemFailures));
    }
  }

  /** A whole sweep attempt threw before any {@link RetentionReport} could be produced at all. */
  public void recordRetentionSweepFailure() {
    safely(() -> Counter.builder("runner.retention.sweep_failures").register(registry).increment());
  }

  /** One of {@code DiskMetricsSampler}'s two independent background samples failed. */
  public void recordSampleFailure(SampleSource source) {
    safely(
        () ->
            Counter.builder("runner.metrics.sampler.failures")
                .tag("source", source.name())
                .register(registry)
                .increment());
  }

  private void safely(Runnable action) {
    try {
      action.run();
    } catch (RuntimeException e) {
      log.warn("Failed to record a metric - continuing without it", e);
    }
  }
}
