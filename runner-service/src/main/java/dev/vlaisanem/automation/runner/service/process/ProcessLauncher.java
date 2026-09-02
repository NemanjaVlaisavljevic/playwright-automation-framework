package dev.vlaisanem.automation.runner.service.process;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Seam between {@code RunService}'s orchestration logic and a real OS process, so the former can be
 * unit-tested with a fake launcher instead of actually spawning Gradle for every scenario
 * (queue-full, cancellation, timeout).
 */
public interface ProcessLauncher {

  /**
   * {@code environment} entries are added on top of the launched process's inherited environment.
   */
  Process start(
      List<String> command, Path workingDirectory, Path outputFile, Map<String, String> environment)
      throws IOException;

  ProcessOutcome awaitCompletion(Process process, Duration timeout);

  /**
   * Kills {@code process} and every descendant in its process tree (graceful signal first, then
   * forced), not just the immediate handle. A single {@link Process#destroyForcibly()} call only
   * reaches the direct child - for a {@code gradlew.bat} invocation that is typically a wrapper
   * script process, while the actual Gradle client/worker JVMs doing the real work are its
   * descendants and would otherwise keep running after a run is already reported CANCELLED or
   * TIMED_OUT. Throws {@link
   * dev.vlaisanem.automation.runner.service.exception.ProcessTerminationException} if one or more
   * processes are still alive after both attempts.
   */
  void terminate(Process process);
}
