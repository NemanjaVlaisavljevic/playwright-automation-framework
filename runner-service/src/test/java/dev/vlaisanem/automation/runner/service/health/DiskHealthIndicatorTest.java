package dev.vlaisanem.automation.runner.service.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vlaisanem.automation.runner.service.disk.DiskUsageService;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService.DiskUsageSnapshot;
import dev.vlaisanem.automation.runner.service.exception.DiskUsageUnavailableException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class DiskHealthIndicatorTest {

  @Test
  void reportsUpWhenUsableSpaceIsAboveTheConfiguredThreshold() {
    DiskUsageService diskUsageService = mock(DiskUsageService.class);
    when(diskUsageService.snapshot())
        .thenReturn(new DiskUsageSnapshot(Long.MAX_VALUE, 1_048_576L, 314_572_800L, Instant.now()));
    DiskHealthIndicator indicator = new DiskHealthIndicator(diskUsageService);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void reportsOutOfServiceWhenBelowTheConfiguredThreshold() {
    DiskUsageService diskUsageService = mock(DiskUsageService.class);
    when(diskUsageService.snapshot())
        .thenReturn(new DiskUsageSnapshot(0L, 1_048_576L, 314_572_800L, Instant.now()));
    DiskHealthIndicator indicator = new DiskHealthIndicator(diskUsageService);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
  }

  /**
   * A probe that cannot determine free space at all is a genuine failure (fail-closed, matching
   * D4.2's own philosophy) - deliberately distinct from the merely-low-disk case above, which
   * reports the softer, self-resolving {@code OUT_OF_SERVICE} instead.
   */
  @Test
  void reportsDownWhenTheDiskUsageProbeItselfFails() {
    DiskUsageService diskUsageService = mock(DiskUsageService.class);
    when(diskUsageService.snapshot())
        .thenThrow(new DiskUsageUnavailableException("simulated probe failure", null));
    DiskHealthIndicator indicator = new DiskHealthIndicator(diskUsageService);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
  }
}
