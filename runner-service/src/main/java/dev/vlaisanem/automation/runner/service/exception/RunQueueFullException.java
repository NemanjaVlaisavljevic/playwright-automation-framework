package dev.vlaisanem.automation.runner.service.exception;

/** Thrown when the bounded run queue has no room for another submission. */
public class RunQueueFullException extends RuntimeException {

  public RunQueueFullException(int capacity) {
    super("Run queue is full (capacity " + capacity + "); try again later");
  }
}
