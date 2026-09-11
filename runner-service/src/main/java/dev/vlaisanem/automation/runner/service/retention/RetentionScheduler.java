package dev.vlaisanem.automation.runner.service.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The periodic background trigger for {@link RetentionService#sweep}. {@code initialDelay = 0} so a
 * run left tombstoned/claimed by a crash mid-cleanup is resumed promptly on the next startup,
 * rather than waiting out a full {@code runner.retention-cleanup-interval}.
 *
 * <p>{@link RetentionService#sweep} already isolates and logs every per-item failure; this class
 * only needs to log the resulting {@link RetentionReport} and catch a whole-sweep failure so it
 * can't propagate out of {@code @Scheduled} - Spring stops rescheduling a method that throws.
 */
@Component
public class RetentionScheduler {

  private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

  private final RetentionService retentionService;

  public RetentionScheduler(RetentionService retentionService) {
    this.retentionService = retentionService;
  }

  @Scheduled(fixedDelayString = "${runner.retention-cleanup-interval}", initialDelayString = "0")
  public void sweep() {
    try {
      RetentionReport report = retentionService.sweep(false);
      if (report.skipped()) {
        // Expected, not an error: a manual POST /api/v1/retention/run was already mid-sweep.
        log.info("Retention sweep skipped this tick - another real sweep was already in progress");
      } else {
        log.info("Retention sweep completed: {}", report);
      }
    } catch (RuntimeException e) {
      log.error("Retention sweep failed - will retry on the next scheduled interval", e);
    }
  }
}
