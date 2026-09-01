package dev.vlaisanem.automation.runner.service.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spawns its own child process (a {@link SleepAndExitFixture}) and writes that child's PID to
 * {@code args[0]}, then sleeps itself - giving {@link GradleProcessRunnerTest} a real two-level
 * process tree (this JVM's child, plus its own grandchild relative to the test) to verify {@link
 * GradleProcessRunner#terminate} kills the whole tree, not just the immediate process.
 */
public final class ProcessTreeFixture {

  private ProcessTreeFixture() {}

  public static void main(String[] args) throws IOException, InterruptedException {
    Path pidFile = Path.of(args[0]);
    String javaExecutable = ProcessHandle.current().info().command().orElseThrow();
    ProcessBuilder builder =
        new ProcessBuilder(
            javaExecutable,
            "-cp",
            System.getProperty("java.class.path"),
            SleepAndExitFixture.class.getName(),
            "600000",
            "0");
    Process child = builder.start();
    Files.writeString(pidFile, Long.toString(child.pid()));
    child.waitFor();
  }
}
