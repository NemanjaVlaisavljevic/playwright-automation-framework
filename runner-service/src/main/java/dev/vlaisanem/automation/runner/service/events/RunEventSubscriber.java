package dev.vlaisanem.automation.runner.service.events;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;

/**
 * Receives events for one run's subscription - both the replay batch and every live event
 * afterward, delivered one at a time, in order, from a single dedicated delivery thread (see {@link
 * RunEventHub}). Never called concurrently with itself for the same subscription.
 */
public interface RunEventSubscriber {

  /**
   * Delivers one event. May throw (e.g. the underlying transport, such as an SSE emitter, failed to
   * write) - doing so permanently closes the subscription and calls {@link #onError} with the
   * thrown exception, exactly like a queue-overflow disconnect would.
   */
  void onEvent(RunnerEvent event) throws Exception;

  /** The subscription closed abnormally - a delivery failure or a disconnect for falling behind. */
  void onError(Throwable cause);

  /** The subscription closed normally, e.g. because the caller itself called {@code close()}. */
  void onComplete();
}
