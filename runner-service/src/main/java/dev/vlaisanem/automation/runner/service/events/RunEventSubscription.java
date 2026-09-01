package dev.vlaisanem.automation.runner.service.events;

/**
 * A handle to one active {@link RunEventSubscriber} registration. Closing it is idempotent, stops
 * further delivery, releases the subscription's own delivery thread, and calls the subscriber's
 * {@link RunEventSubscriber#onComplete()} exactly once - never {@code onError}, which is reserved
 * for the subscription closing on its own (a delivery failure or a slow-consumer disconnect).
 */
public interface RunEventSubscription {

  void close();
}
