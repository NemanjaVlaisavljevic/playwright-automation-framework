package dev.vlaisanem.automation.runner.service.exception;

/**
 * Thrown when one client IP already holds {@code runner.sse-max-connections-per-ip} concurrent
 * event-stream subscriptions (D3.3) - distinct from {@link RunEventSubscriptionRejectedException},
 * which reflects the server's own total-capacity ceiling rather than one client's fair share of it.
 * Mapped to {@code 429}, not {@code 503}: this is an abuse-protection limit on the caller, not a
 * statement that the server itself is degraded.
 */
public class SseConnectionLimitExceededException extends RuntimeException {

  public SseConnectionLimitExceededException(int maxConnectionsPerIp) {
    super(
        "This client already holds the maximum of "
            + maxConnectionsPerIp
            + " concurrent event-stream connections allowed - close one before opening another.");
  }
}
