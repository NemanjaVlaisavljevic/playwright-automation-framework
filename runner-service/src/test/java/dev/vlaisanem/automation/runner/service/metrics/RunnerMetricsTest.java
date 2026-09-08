package dev.vlaisanem.automation.runner.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.retention.RetentionReport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * D4.3.2 - proves {@link RunnerMetrics}'s own two central guarantees directly, against a plain
 * {@link SimpleMeterRegistry} (no Spring context needed): cardinality-locked tag values (every
 * enum, never a free-form string) and best-effort recording (a broken registry never throws out of
 * a {@code record*} call).
 */
class RunnerMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final RunnerMetrics metrics = new RunnerMetrics(registry);

  @Test
  void recordRunSubmittedIncrementsTheSuiteTaggedCounter() {
    metrics.recordRunSubmitted(Suite.SMOKE);

    assertThat(registry.find("runner.runs.submitted").tag("suite", "SMOKE").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void recordRunStartedIncrementsTheSuiteTaggedCounter() {
    metrics.recordRunStarted(Suite.API);

    assertThat(registry.find("runner.runs.started").tag("suite", "API").counter().count())
        .isEqualTo(1.0);
  }

  @Test
  void recordRunFinishedRecordsBothTheCounterAndTheDurationWhenStartedAtIsPresent() {
    Instant startedAt = Instant.parse("2026-09-08T10:00:00Z");
    Instant finishedAt = Instant.parse("2026-09-08T10:00:05Z");

    metrics.recordRunFinished(Suite.SMOKE, RunStatus.SUCCEEDED, startedAt, finishedAt);

    assertThat(
            registry
                .find("runner.runs.finished")
                .tag("suite", "SMOKE")
                .tag("status", "SUCCEEDED")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            registry
                .find("runner.runs.duration")
                .tag("suite", "SMOKE")
                .tag("status", "SUCCEEDED")
                .timer())
        .satisfies(
            timer -> {
              assertThat(timer.count()).isEqualTo(1L);
              assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(5.0);
            });
  }

  @Test
  void recordRunFinishedSkipsDurationWhenStartedAtIsNull() {
    metrics.recordRunFinished(
        Suite.SMOKE, RunStatus.CANCELLED, null, Instant.parse("2026-09-08T10:00:00Z"));

    assertThat(
            registry
                .find("runner.runs.finished")
                .tag("suite", "SMOKE")
                .tag("status", "CANCELLED")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            registry
                .find("runner.runs.duration")
                .tag("suite", "SMOKE")
                .tag("status", "CANCELLED")
                .timer())
        .isNull();
  }

  @Test
  void recordRunFinishedRejectsANonTerminalStatus() {
    assertThatThrownBy(
            () ->
                metrics.recordRunFinished(
                    Suite.SMOKE, RunStatus.RUNNING, Instant.now(), Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void recordDiskRejectionTagsTheExactPhase() {
    metrics.recordDiskRejection(RunnerMetrics.DiskRejectionPhase.SUBMIT);
    metrics.recordDiskRejection(RunnerMetrics.DiskRejectionPhase.PRE_LAUNCH);

    assertThat(
            registry.find("runner.runs.disk_rejections").tag("phase", "SUBMIT").counter().count())
        .isEqualTo(1.0);
    assertThat(
            registry
                .find("runner.runs.disk_rejections")
                .tag("phase", "PRE_LAUNCH")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void recordSseRejectionTagsTheExactReason() {
    metrics.recordSseRejection(RunnerMetrics.SseRejectionReason.GLOBAL_CAP);
    metrics.recordSseRejection(RunnerMetrics.SseRejectionReason.PER_IP_CAP);
    metrics.recordSseRejection(RunnerMetrics.SseRejectionReason.PER_IP_CAP);

    assertThat(
            registry
                .find("runner.sse.connections.rejected")
                .tag("reason", "GLOBAL_CAP")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            registry
                .find("runner.sse.connections.rejected")
                .tag("reason", "PER_IP_CAP")
                .counter()
                .count())
        .isEqualTo(2.0);
  }

  @Test
  void recordRunsRecoveredIncrementsByTheGivenCount() {
    metrics.recordRunsRecovered(3);

    assertThat(registry.find("runner.recovery.recovered").counter().count()).isEqualTo(3.0);
  }

  @Test
  void recordRunsRecoveredNeverRegistersACounterForZero() {
    metrics.recordRunsRecovered(0);

    assertThat(registry.find("runner.recovery.recovered").counter()).isNull();
  }

  @Test
  void recordRetentionIncrementsRunsDeletedAndBytesFreedButNotItemFailuresWhenThereAreNone() {
    RetentionReport report = new RetentionReport(false, 2, 2, 0, 1, 1, 0, 4096L, false);

    metrics.recordRetention(report);

    assertThat(registry.find("runner.retention.runs_deleted").counter().count()).isEqualTo(2.0);
    assertThat(registry.find("runner.retention.bytes_freed").counter().count()).isEqualTo(4096.0);
    assertThat(registry.find("runner.retention.item_failures").counter()).isNull();
  }

  @Test
  void recordRetentionIncrementsItemFailuresWhenSomeExist() {
    RetentionReport report = new RetentionReport(false, 3, 1, 1, 2, 1, 1, 1024L, false);

    metrics.recordRetention(report);

    assertThat(registry.find("runner.retention.item_failures").counter().count()).isEqualTo(2.0);
  }

  @Test
  void recordRetentionSweepFailureIncrementsItsOwnCounter() {
    metrics.recordRetentionSweepFailure();

    assertThat(registry.find("runner.retention.sweep_failures").counter().count()).isEqualTo(1.0);
  }

  @Test
  void recordSampleFailureTagsTheExactSource() {
    metrics.recordSampleFailure(RunnerMetrics.SampleSource.RUNNER_DATA);
    metrics.recordSampleFailure(RunnerMetrics.SampleSource.DATABASE);

    assertThat(
            registry
                .find("runner.metrics.sampler.failures")
                .tag("source", "RUNNER_DATA")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            registry
                .find("runner.metrics.sampler.failures")
                .tag("source", "DATABASE")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  /**
   * The correctness-critical guarantee: a genuine Micrometer/registry failure must never propagate
   * out of a {@code record*} call and change a run's outcome - only swallowed and logged.
   */
  @Test
  void aBrokenRegistryNeverThrowsOutOfAnyRecordCall() {
    MeterRegistry brokenRegistry =
        mock(
            MeterRegistry.class,
            invocation -> {
              throw new RuntimeException("simulated registry failure");
            });
    RunnerMetrics brokenMetrics = new RunnerMetrics(brokenRegistry);

    assertThatCode(() -> brokenMetrics.recordRunSubmitted(Suite.SMOKE)).doesNotThrowAnyException();
    assertThatCode(() -> brokenMetrics.recordRunStarted(Suite.SMOKE)).doesNotThrowAnyException();
    assertThatCode(
            () ->
                brokenMetrics.recordRunFinished(
                    Suite.SMOKE, RunStatus.SUCCEEDED, Instant.now(), Instant.now()))
        .doesNotThrowAnyException();
    assertThatCode(() -> brokenMetrics.recordDiskRejection(RunnerMetrics.DiskRejectionPhase.SUBMIT))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> brokenMetrics.recordSseRejection(RunnerMetrics.SseRejectionReason.GLOBAL_CAP))
        .doesNotThrowAnyException();
    assertThatCode(() -> brokenMetrics.recordRunsRecovered(1)).doesNotThrowAnyException();
    assertThatCode(
            () ->
                brokenMetrics.recordRetention(
                    new RetentionReport(false, 1, 1, 0, 0, 0, 0, 100L, false)))
        .doesNotThrowAnyException();
    assertThatCode(brokenMetrics::recordRetentionSweepFailure).doesNotThrowAnyException();
    assertThatCode(() -> brokenMetrics.recordSampleFailure(RunnerMetrics.SampleSource.RUNNER_DATA))
        .doesNotThrowAnyException();
  }
}
