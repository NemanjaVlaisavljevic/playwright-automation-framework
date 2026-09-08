package dev.vlaisanem.automation.runner.service.health;

import dev.vlaisanem.automation.runner.service.disk.DiskUsageService;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService.DiskUsageSnapshot;
import dev.vlaisanem.automation.runner.service.exception.DiskUsageUnavailableException;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * D4.3.1 - contributes to the {@code readiness} health group under the contributor name {@code
 * disk} (Spring Boot strips the {@code HealthIndicator} suffix from this bean's own name) - proven,
 * not merely assumed, by {@code HealthEndpointGroupMembershipTest}.
 *
 * <p>{@link Status#OUT_OF_SERVICE}, never {@link Status#DOWN}, when {@link
 * DiskUsageSnapshot#belowThreshold()} - D4.2's own guard already treats this as temporary and
 * self-resolving (D4.1's retention sweep, or an operator freeing space), never something a restart
 * would fix. A {@link DiskUsageUnavailableException} from the snapshot call itself - the probe
 * genuinely cannot determine free space - is the one case here that *does* report {@link
 * Status#DOWN}: a fail-closed guard that cannot answer its own question is a real failure, not a
 * merely-low-disk condition. Deliberately excluded from the {@code liveness} group: disk pressure
 * is never a reason to restart the JVM.
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
