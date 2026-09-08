package dev.vlaisanem.automation.runner.service.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vlaisanem.automation.runner.service.orchestration.RunRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class RecoveryHealthIndicatorTest {

  @Test
  void reportsOutOfServiceWhileRecoveryIsStillRunning() {
    RunRecoveryService recoveryService = mock(RunRecoveryService.class);
    when(recoveryService.isRecoveryComplete()).thenReturn(false);
    RecoveryHealthIndicator indicator = new RecoveryHealthIndicator(recoveryService);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
  }

  @Test
  void reportsUpOnceRecoveryHasFinished() {
    RunRecoveryService recoveryService = mock(RunRecoveryService.class);
    when(recoveryService.isRecoveryComplete()).thenReturn(true);
    RecoveryHealthIndicator indicator = new RecoveryHealthIndicator(recoveryService);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }
}
