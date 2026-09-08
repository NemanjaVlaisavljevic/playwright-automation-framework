package dev.vlaisanem.automation.runner.service.exception;

/**
 * Thrown when {@code DiskUsageService} cannot determine current free disk space at all (the
 * underlying {@code FileStore} query itself failed) - deliberately a distinct, fail-closed 503, not
 * silently treated as "space available" and not left to fall through to a generic 500. A disk-space
 * guard that cannot answer its own question must refuse work, not guess.
 */
public class DiskUsageUnavailableException extends RuntimeException {

  public DiskUsageUnavailableException(String detail, Throwable cause) {
    super("Runner cannot currently determine available disk space: " + detail, cause);
  }
}
