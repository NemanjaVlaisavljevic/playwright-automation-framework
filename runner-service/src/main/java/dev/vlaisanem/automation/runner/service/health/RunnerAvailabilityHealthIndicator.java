package dev.vlaisanem.automation.runner.service.health;

import dev.vlaisanem.automation.runner.service.orchestration.RunService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * Contributes to the {@code readiness} health group as {@code runnerAvailability}. Reports {@link
 * Status#OUT_OF_SERVICE} while {@link RunService} is {@code DEGRADED} (a survivor process tree from
 * a previous run hasn't exited yet) - the reaper clears it automatically. Excluded from {@code
 * liveness}.
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
