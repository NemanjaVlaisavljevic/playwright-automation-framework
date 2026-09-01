package dev.vlaisanem.automation.runner.service.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Enforces which {@link RunStatus} transitions are valid, so an invalid one (e.g. skipping straight
 * from {@code QUEUED} to {@code RUNNING}, or moving out of a terminal status) fails loudly instead
 * of silently corrupting a run's history.
 */
public final class RunStateMachine {

  private static final Map<RunStatus, Set<RunStatus>> ALLOWED_TRANSITIONS = buildTransitions();

  private RunStateMachine() {}

  public static void requireTransition(RunStatus from, RunStatus to) {
    if (!ALLOWED_TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
      throw new IllegalStateException("Cannot transition a run from " + from + " to " + to);
    }
  }

  private static Map<RunStatus, Set<RunStatus>> buildTransitions() {
    Map<RunStatus, Set<RunStatus>> transitions = new EnumMap<>(RunStatus.class);
    transitions.put(
        RunStatus.QUEUED, EnumSet.of(RunStatus.STARTING, RunStatus.CANCELLED, RunStatus.ERROR));
    transitions.put(
        RunStatus.STARTING, EnumSet.of(RunStatus.RUNNING, RunStatus.ERROR, RunStatus.CANCELLED));
    transitions.put(
        RunStatus.RUNNING,
        EnumSet.of(
            RunStatus.SUCCEEDED,
            RunStatus.FAILED,
            RunStatus.CANCELLED,
            RunStatus.TIMED_OUT,
            RunStatus.ERROR));
    // Terminal statuses are absent from this map entirely - getOrDefault(..., Set.of()) then
    // rejects every transition out of them, which is the point.
    return Map.copyOf(transitions);
  }
}
