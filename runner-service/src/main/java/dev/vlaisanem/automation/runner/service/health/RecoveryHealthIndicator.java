package dev.vlaisanem.automation.runner.service.health;

import dev.vlaisanem.automation.runner.service.orchestration.RunRecoveryService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * D4.3.1 - contributes to the {@code readiness} health group (see {@code
 * management.endpoint.health.group.readiness.include} in {@code application.yml}), registered under
 * the contributor name {@code recovery} (Spring Boot strips the {@code HealthIndicator} suffix from
 * this bean's own name) - proven, not merely assumed, by {@code HealthEndpointGroupMembershipTest}.
 *
 * <p>{@link Status#OUT_OF_SERVICE}, never {@link Status#DOWN}, while D2.5's one-time startup
 * recovery pass has not finished yet - the same "temporary, self-resolving" convention {@code
 * RunnerRecoveringException}'s existing 503 mapping already uses. Deliberately excluded from the
 * {@code liveness} group: recovery taking time is never a reason to restart the JVM.
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
