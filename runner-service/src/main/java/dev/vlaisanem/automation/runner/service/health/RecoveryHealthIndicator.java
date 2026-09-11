package dev.vlaisanem.automation.runner.service.health;

import dev.vlaisanem.automation.runner.service.orchestration.RunRecoveryService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * Contributes to the {@code readiness} health group as {@code recovery}. Reports {@link
 * Status#OUT_OF_SERVICE} while startup recovery is still running - the same
 * temporary/self-resolving convention {@code RunnerRecoveringException}'s 503 uses. Excluded from
 * {@code liveness}.
 */
@Component
public class RecoveryHealthIndicator implements HealthIndicator {

  private final RunRecoveryService recoveryService;

  public RecoveryHealthIndicator(RunRecoveryService recoveryService) {
    this.recoveryService = recoveryService;
  }

  @Override
  public Health health() {
    if (!recoveryService.isRecoveryComplete()) {
      return Health.status(Status.OUT_OF_SERVICE).build();
    }
    return Health.up().build();
  }
}
