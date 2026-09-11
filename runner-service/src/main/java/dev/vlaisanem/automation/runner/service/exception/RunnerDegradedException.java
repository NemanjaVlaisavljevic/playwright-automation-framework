package dev.vlaisanem.automation.runner.service.exception;

import java.util.List;

/**
 * Thrown when the runner refuses a new submission because a process tree from a previous
 * cancelled/failed run is still alive - starting a new run now would break single-run isolation.
 */
public class RunnerDegradedException extends RuntimeException {

  public RunnerDegradedException(List<Long> survivingPids) {
    super(
        "Runner is degraded: a process tree from a previous run failed to terminate and is still"
            + " alive; PIDs: "
            + survivingPids);
  }
}
