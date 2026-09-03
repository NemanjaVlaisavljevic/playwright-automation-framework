package dev.vlaisanem.automation.runner.contract;

/** Identifies which entity an event describes and therefore which fields it may carry. */
public enum EventScope {
  RUN,
  TEST,
  STEP
}
