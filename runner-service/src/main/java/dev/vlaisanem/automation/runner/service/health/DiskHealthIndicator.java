package dev.vlaisanem.automation.runner.service.health;

import dev.vlaisanem.automation.runner.service.disk.DiskUsageService;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService.DiskUsageSnapshot;
import dev.vlaisanem.automation.runner.service.exception.DiskUsageUnavailableException;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * Contributes to the {@code readiness} health group as {@code disk}. Reports {@link
 * Status#OUT_OF_SERVICE} (not {@link Status#DOWN}) when {@link DiskUsageSnapshot#belowThreshold()},
 * since low disk is temporary/self-resolving; a {@link DiskUsageUnavailableException} reports
 * {@link Status#DOWN} since the probe itself failed. Excluded from {@code liveness}.
 */
@Component
public class DiskHealthIndicator implements HealthIndicator {

  private final DiskUsageService diskUsageService;

  public DiskHealthIndicator(DiskUsageService diskUsageService) {
    this.diskUsageService = diskUsageService;
  }

  @Override
  public Health health() {
    DiskUsageSnapshot snapshot;
    try {
      snapshot = diskUsageService.snapshot();
    } catch (DiskUsageUnavailableException e) {
      return Health.down(e).build();
    }
    if (snapshot.belowThreshold()) {
      return Health.status(Status.OUT_OF_SERVICE).build();
    }
    return Health.up().build();
  }
}
