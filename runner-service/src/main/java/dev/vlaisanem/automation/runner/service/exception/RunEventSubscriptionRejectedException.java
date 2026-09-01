package dev.vlaisanem.automation.runner.service.exception;

/**
 * Thrown when a new SSE event-stream subscription cannot be accepted - either the hub is already at
 * its configured concurrent-subscriber capacity, or the service is shutting down. Either way, the
 * expected client behavior is the same: back off and reconnect with {@code Last-Event-ID} to
 * resume, rather than treating it as a permanent failure.
 */
public class RunEventSubscriptionRejectedException extends RuntimeException {

  public RunEventSubscriptionRejectedException(String message) {
    super(message);
  }
}
