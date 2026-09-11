package dev.vlaisanem.automation.runner.service.exception;

/**
 * Thrown when a new SSE subscription can't be accepted (hub at capacity, or shutting down). Clients
 * should back off and reconnect with {@code Last-Event-ID} rather than treat this as permanent.
 */
public class RunEventSubscriptionRejectedException extends RuntimeException {

  public RunEventSubscriptionRejectedException(String message) {
    super(message);
  }
}
