package dev.vlaisanem.automation.runner.service.disk;

import dev.vlaisanem.automation.runner.service.disk.DiskUsageService.DiskUsageSnapshot;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only operational tooling, not a dashboard feature, so this is {@link Hidden} from the
 * public OpenAPI document like {@code RetentionController}. Requires {@code ROLE_ADMIN} and its own
 * independent rate-limit budget - reached via {@code curl}/admin ops tooling only.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/disk")
public class DiskUsageController {

  private final DiskUsageService diskUsageService;

  public DiskUsageController(DiskUsageService diskUsageService) {
    this.diskUsageService = diskUsageService;
  }

  @GetMapping("/usage")
  public DiskUsageResponse usage() {
    DiskUsageSnapshot snapshot = diskUsageService.snapshot();
    return new DiskUsageResponse(
        diskUsageService.runnerDataBytes(),
        snapshot.usableFreeBytes(),
        diskUsageService.databaseBytes(),
        snapshot.diskMinFreeBytes(),
        snapshot.runMaxDiskBytes(),
        snapshot.belowThreshold());
  }

  /**
   * @param runnerDataBytes total bytes currently under this service's own {@code runner-data}
   *     directories (raw events, logs, artifacts).
   * @param usableFreeBytes live usable free space on the filesystem backing them.
   * @param databaseBytes {@code pg_database_size(current_database())} - one database's size, never
   *     the real {@code pgdata} volume's full footprint (WAL, temp files, other databases, cluster
   *     overhead sit outside this number).
   * @param diskMinFreeBytes the configured floor a submit/pre-launch guard enforces stays free.
   * @param runMaxDiskBytes the worst-case total disk one starting run can still consume.
   * @param belowThreshold whether a new run would currently be rejected for low disk space.
   */
  public record DiskUsageResponse(
      long runnerDataBytes,
      long usableFreeBytes,
      long databaseBytes,
      long diskMinFreeBytes,
      long runMaxDiskBytes,
      boolean belowThreshold) {}
}
