package dev.vlaisanem.automation.runner.service.process;

/**
 * What actually happened to an OS process once {@link ProcessLauncher#awaitCompletion} returns.
 * Deliberately does not have a CANCELLED variant: cancellation is layered on top by {@code
 * RunService}, which alone knows whether it requested the kill that produced this outcome - from
 * the process's own point of view, a cancelled run and a run that happened to exit right as its
 * deadline passed look identical.
 */
public record ProcessOutcome(Kind kind, int exitCode) {

  public enum Kind {
    COMPLETED,
    TIMED_OUT
  }

  public static ProcessOutcome completed(int exitCode) {
    return new ProcessOutcome(Kind.COMPLETED, exitCode);
  }

  public static ProcessOutcome timedOut() {
    return new ProcessOutcome(Kind.TIMED_OUT, -1);
  }
}
