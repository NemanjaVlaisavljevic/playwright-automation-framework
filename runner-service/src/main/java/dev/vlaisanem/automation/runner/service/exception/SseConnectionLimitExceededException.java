package dev.vlaisanem.automation.runner.service.exception;

/**
 * Thrown when one client IP already holds {@code runner.sse-max-connections-per-ip} concurrent
 * subscriptions - distinct from {@link RunEventSubscriptionRejectedException}'s server-wide
 * capacity ceiling. Mapped to {@code 429}: it limits the caller, not a degraded server.
 */
public class SseConnectionLimitExceededException extends RuntimeException {

  public SseConnectionLimitExceededException(int maxConnectionsPerIp) {
    super(
        "This client already holds the maximum of "
            + maxConnectionsPerIp
            + " concurrent event-stream connections allowed - close one before opening another.");
  }
}
