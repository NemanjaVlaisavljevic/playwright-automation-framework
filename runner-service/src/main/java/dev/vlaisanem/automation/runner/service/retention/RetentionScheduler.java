package dev.vlaisanem.automation.runner.service.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * D4.1 - the periodic background trigger for {@link RetentionService#sweep}. {@code initialDelay =
 * 0} so a run left tombstoned/claimed by a crash mid-cleanup is resumed promptly on the very next
 * startup, not only after waiting out a full {@code runner.retention-cleanup-interval} - the same
 * "don't leave a known-bad state sitting" reasoning {@code RunRecoveryService} already applies at
 * startup, just for a periodic job instead of a one-time gate.
 *
 * <p>{@link RetentionService#sweep} already isolates and logs every individual run/artifact-purge
 * failure internally (see its own Javadoc) - this class only needs to log the resulting {@link
 * RetentionReport} itself, and to catch a failure in {@code sweep} as a whole (e.g. the database
 * being briefly unreachable), which must never propagate out of a {@code @Scheduled} method: Spring
 * silently stops scheduling further invocations of a method that throws, which would otherwise turn
 * one transient failure into "retention never runs again for the life of this process."
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
        // Expected, not an error: a manual POST /api/v1/retention/run was already mid-sweep when
        // this tick fired - see RetentionService's own in-process concurrency guard.
        log.info("Retention sweep skipped this tick - another real sweep was already in progress");
      } else {
        log.info("Retention sweep completed: {}", report);
      }
    } catch (RuntimeException e) {
      log.error("Retention sweep failed - will retry on the next scheduled interval", e);
    }
  }
}
