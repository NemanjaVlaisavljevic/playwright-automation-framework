package dev.vlaisanem.automation.runner.service.exception;

/**
 * Thrown when {@code DiskUsageService} cannot determine free disk space at all (the underlying
 * {@code FileStore} query failed). Fails closed as its own 503 rather than assuming space is
 * available or falling through to a generic 500.
 */
public class DiskUsageUnavailableException extends RuntimeException {

  public DiskUsageUnavailableException(String detail, Throwable cause) {
    super("Runner cannot currently determine available disk space: " + detail, cause);
  }
}
