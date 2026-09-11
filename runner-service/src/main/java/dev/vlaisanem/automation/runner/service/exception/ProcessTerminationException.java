package dev.vlaisanem.automation.runner.service.exception;

import java.util.ArrayList;
import java.util.List;

/**
 * Raised when one or more processes remain alive after graceful and forced tree termination. Holds
 * the {@link Process}/{@link ProcessHandle} references rather than raw PIDs, since the OS can reuse
 * a PID once the original process is gone.
 */
public class ProcessTerminationException extends RuntimeException {

  private final Process rootProcess;
  private final List<ProcessHandle> survivingDescendants;

  public ProcessTerminationException(
      Process rootProcess, List<ProcessHandle> survivingDescendants) {
    super(
        "Process tree is still alive after forced termination; surviving PIDs: "
            + survivingPids(rootProcess, survivingDescendants));
    this.rootProcess = rootProcess;
    this.survivingDescendants = List.copyOf(survivingDescendants);
  }

  /**
   * Whether the root process or any originally-surviving descendant is still alive right now -
   * queries live state on every call, so a caller (a background reaper) can poll this repeatedly
   * until it finally reports {@code false}.
   */
  public boolean anySurvivorStillAlive() {
    return rootProcess.isAlive() || survivingDescendants.stream().anyMatch(ProcessHandle::isAlive);
  }

  /** PIDs still alive right now - recomputed live, not a frozen snapshot from construction time. */
  public List<Long> survivingPids() {
    return survivingPids(rootProcess, survivingDescendants);
  }

  private static List<Long> survivingPids(Process rootProcess, List<ProcessHandle> descendants) {
    List<Long> pids = new ArrayList<>();
    if (rootProcess.isAlive()) {
      pids.add(rootProcess.pid());
    }
    descendants.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).forEach(pids::add);
    return List.copyOf(pids);
  }
}
