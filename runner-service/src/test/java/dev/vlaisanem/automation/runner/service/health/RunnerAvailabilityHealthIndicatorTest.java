package dev.vlaisanem.automation.runner.service.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vlaisanem.automation.runner.service.orchestration.RunService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class RunnerAvailabilityHealthIndicatorTest {

  @Test
  void reportsOutOfServiceWhileDegraded() {
    RunService runService = mock(RunService.class);
    when(runService.isDegraded()).thenReturn(true);
    RunnerAvailabilityHealthIndicator indicator = new RunnerAvailabilityHealthIndicator(runService);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
  }

  @Test
  void reportsUpWhenAvailable() {
    RunService runService = mock(RunService.class);
    when(runService.isDegraded()).thenReturn(false);
    RunnerAvailabilityHealthIndicator indicator = new RunnerAvailabilityHealthIndicator(runService);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }
}
