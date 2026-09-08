package dev.vlaisanem.automation.runner.service.health;

import dev.vlaisanem.automation.runner.service.orchestration.RunService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * D4.3.1 - contributes to the {@code readiness} health group under the contributor name {@code
 * runnerAvailability} (Spring Boot strips the {@code HealthIndicator} suffix from this bean's own
 * name) - proven, not merely assumed, by {@code HealthEndpointGroupMembershipTest}.
 *
 * <p>{@link Status#OUT_OF_SERVICE}, never {@link Status#DOWN}, while {@link RunService} is {@code
 * DEGRADED} (a process tree from a previous run failed to terminate) - the background reaper clears
 * this on its own once every known survivor actually exits, the same "temporary, self-resolving"
 * convention {@code RunnerDegradedException}'s existing 503 mapping already uses. Deliberately
 * excluded from the {@code liveness} group: a stuck survivor process is never a reason to restart
 * the JVM that is itself waiting for it to exit.
 */
@Component
public class RunnerAvailabilityHealthIndicator implements HealthIndicator {

  private final RunService runService;

  public RunnerAvailabilityHealthIndicator(RunService runService) {
    this.runService = runService;
  }

  @Override
  public Health health() {
    if (runService.isDegraded()) {
      return Health.status(Status.OUT_OF_SERVICE).build();
    }
    return Health.up().build();
  }
}
