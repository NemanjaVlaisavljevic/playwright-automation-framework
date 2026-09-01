package dev.vlaisanem.automation.runner.service.exception;

import java.util.List;

/**
 * Thrown when the runner refuses a new submission because a process tree from a previously
 * cancelled/failed run is still known to be alive - starting a new run now would break single-run
 * isolation by letting it execute concurrently with that survivor.
 */
public class RunnerDegradedException extends RuntimeException {

  public RunnerDegradedException(List<Long> survivingPids) {
    super(
        "Runner is degraded: a process tree from a previous run failed to terminate and is still"
            + " alive; PIDs: "
            + survivingPids);
  }
}
