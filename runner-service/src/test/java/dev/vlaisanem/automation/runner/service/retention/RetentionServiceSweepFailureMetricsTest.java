package dev.vlaisanem.automation.runner.service.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vlaisanem.automation.runner.service.artifacts.ArtifactRepository;
import dev.vlaisanem.automation.runner.service.config.RateLimitRule;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.metrics.RunnerMetrics;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@code RunnerMetricsTest} proves {@code recordRetentionSweepFailure} itself works, and {@code
 * RetentionServiceTest} proves the success path against a real sweep; this proves the call-site
 * wiring for a genuine whole-sweep exception that fails before any {@link RetentionReport} could be
 * built. A plain Mockito {@link RunLifecycleStore} is enough, since the exception fires on the very
 * first call {@code computeCandidates()} makes.
 */
class RetentionServiceSweepFailureMetricsTest {

  @Test
  void aWholeSweepExceptionIncrementsSweepFailuresAndRethrowsUnchanged() {
    RunLifecycleStore runStore = mock(RunLifecycleStore.class);
    when(runStore.findPendingCleanup()).thenThrow(new RuntimeException("simulated DB failure"));
    ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RunnerMetrics metrics = new RunnerMetrics(registry);
    RetentionService retentionService =
        new RetentionService(runStore, artifactRepository, testProperties(), metrics);

    assertThatThrownBy(() -> retentionService.sweep(false))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("simulated DB failure");

    assertThat(registry.find("runner.retention.sweep_failures").counter().count()).isEqualTo(1.0);
    assertThat(registry.find("runner.retention.runs_deleted").counter()).isNull();
  }

  private static RunnerProperties testProperties() {
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
        Duration.ofSeconds(60));
  }
}
