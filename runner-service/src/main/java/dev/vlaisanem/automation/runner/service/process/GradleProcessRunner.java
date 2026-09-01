package dev.vlaisanem.automation.runner.service.process;

import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.exception.ProcessTerminationException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Launches a Gradle invocation and waits for it, enforcing a hard timeout. */
@Component
public class GradleProcessRunner implements ProcessLauncher {

  private static final Logger log = LoggerFactory.getLogger(GradleProcessRunner.class);
  private static final byte[] TRUNCATION_MARKER =
      "\n--- process log truncated: configured byte limit reached ---\n"
          .getBytes(StandardCharsets.UTF_8);
  private static final int MAX_FORCED_KILL_PASSES = 3;

  private final Duration terminationGracePeriod;
  private final long processLogMaxBytes;
  private final Map<Process, Thread> outputDrainers = new ConcurrentHashMap<>();

  public GradleProcessRunner(RunnerProperties properties) {
    this.terminationGracePeriod = properties.terminationGracePeriod();
    this.processLogMaxBytes = properties.processLogMaxBytes();
  }

  @Override
  public Process start(List<String> command, Path workingDirectory, Path outputFile)
      throws IOException {
    Path absoluteOutputFile = outputFile.toAbsolutePath().normalize();
    Path parent = absoluteOutputFile.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    OutputStream output =
        Files.newOutputStream(
            absoluteOutputFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    ProcessBuilder builder =
        new ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true);
    try {
      Process process = builder.start();
      drainOutput(process, output, absoluteOutputFile);
      return process;
    } catch (IOException | RuntimeException exception) {
      try {
        output.close();
      } catch (IOException closeFailure) {
        exception.addSuppressed(closeFailure);
      }
      throw exception;
    }
  }

  @Override
  public ProcessOutcome awaitCompletion(Process process, Duration timeout) {
    boolean finishedInTime;
    try {
      finishedInTime = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      // Can no longer wait for a real exit code, and the process is being killed regardless -
      // treat this the same as the deadline having passed.
      terminate(process);
      return ProcessOutcome.timedOut();
    }
    if (!finishedInTime) {
      terminate(process);
      return ProcessOutcome.timedOut();
    }
    awaitOutputDrainer(process);
    return ProcessOutcome.completed(process.exitValue());
  }

  /**
   * Iterative collect-and-kill: a single snapshot (even taken twice) cannot fully protect against a
   * descendant that spawns its own child moments before dying and getting reparented away - once
   * reparented, it is no longer visible through {@code process.descendants()} at all. Discovery
   * (via the root AND every still-alive already-known handle, which can reveal a grandchild the
   * root's own view misses) and the matching kill signal both repeat on every poll tick of the wait
   * itself - see {@link #allDeadWithin} - not just once per pass, so a process that only appears
   * mid-wait is still caught, and a fresh discovery-and-kill always immediately precedes the check
   * that decides success. Each pass still always kills the root only after every currently-known
   * descendant, but does not guarantee an ordering among the descendants themselves (e.g. a
   * grandchild before its own child) - {@code known} preserves discovery order, not tree depth.
   * This narrows the race rather than eliminating it - a Windows Job Object / POSIX process group
   * would be the airtight fix, deferred as a deliberate follow-up rather than attempted here.
   */
  @Override
  public void terminate(Process process) {
    Map<Long, ProcessHandle> known = new LinkedHashMap<>();

    Runnable gracefulKill =
        () -> {
          known.values().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
          process.destroy();
        };
    if (allDeadWithin(process, known, terminationGracePeriod, gracefulKill)) {
      awaitOutputDrainer(process);
      return;
    }

    Runnable forcedKill =
        () -> {
          known.values().stream()
              .filter(ProcessHandle::isAlive)
              .forEach(ProcessHandle::destroyForcibly);
          process.destroyForcibly();
        };
    for (int pass = 1; pass <= MAX_FORCED_KILL_PASSES; pass++) {
      if (allDeadWithin(process, known, terminationGracePeriod, forcedKill)) {
        awaitOutputDrainer(process);
        return;
      }
    }

    throw new ProcessTerminationException(process, List.copyOf(known.values()));
  }

  /**
   * Adds any not-yet-known descendant of {@code process}, or of any still-alive already-known
   * handle, into {@code known} (keyed by PID to dedupe). Scanning every known-alive handle's own
   * descendants too - not just the root's - is what can still catch a grandchild after its
   * immediate parent has already been reparented away from {@code process}'s view.
   */
  private void discoverNewDescendants(Process process, Map<Long, ProcessHandle> known) {
    process.descendants().forEach(handle -> known.putIfAbsent(handle.pid(), handle));
    List.copyOf(known.values()).stream()
        .filter(ProcessHandle::isAlive)
        .forEach(handle -> handle.descendants().forEach(d -> known.putIfAbsent(d.pid(), d)));
  }

  /**
   * Waits up to {@code timeout} for everything in {@code known} (plus {@code process} itself) to
   * die, re-discovering descendants and re-applying {@code killEverything} on every poll tick - not
   * just once at entry - so a process that appears only during the wait is still found and killed,
   * and the very last discovery-and-kill always happens immediately before the final aliveness
   * check, whichever way it comes out.
   */
  private boolean allDeadWithin(
      Process process, Map<Long, ProcessHandle> known, Duration timeout, Runnable killEverything) {
    Instant deadline = Instant.now().plus(timeout);
    while (true) {
      discoverNewDescendants(process, known);
      killEverything.run();
      if (!process.isAlive() && known.values().stream().noneMatch(ProcessHandle::isAlive)) {
        return true;
      }
      if (!Instant.now().isBefore(deadline)) {
        return false;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }

  // Drains combined stdout/stderr for the whole lifetime of the process so its pipe cannot fill.
  // Retention is bounded per run: once the configured byte limit is reached, the thread continues
  // draining but discards the remainder after writing one explicit truncation marker.
  private void drainOutput(Process process, OutputStream output, Path outputFile) {
    Thread drainer =
        new Thread(
            () -> drainToBoundedFile(process, output, outputFile),
            "gradle-process-output-drain-" + process.pid());
    drainer.setDaemon(true);
    outputDrainers.put(process, drainer);
    drainer.start();
  }

  private void awaitOutputDrainer(Process process) {
    Thread drainer = outputDrainers.remove(process);
    if (drainer == null) {
      return;
    }
    try {
      drainer.join(terminationGracePeriod.toMillis());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return;
    }
    if (drainer.isAlive()) {
      log.warn("Process output drainer did not finish within {}", terminationGracePeriod);
    }
  }

  private void drainToBoundedFile(Process process, OutputStream output, Path outputFile) {
    long contentLimit = processLogMaxBytes - TRUNCATION_MARKER.length;
    long written = 0;
    boolean truncated = false;
    boolean outputWritable = true;
    byte[] buffer = new byte[8192];
    try (InputStream input = process.getInputStream();
        output) {
      int read;
      while ((read = input.read(buffer)) != -1) {
        if (!outputWritable) {
          continue;
        }
        int writable = (int) Math.min(read, Math.max(0, contentLimit - written));
        try {
          if (writable > 0) {
            output.write(buffer, 0, writable);
            written += writable;
          }
          if (writable < read) {
            truncated = true;
          }
        } catch (IOException writeFailure) {
          outputWritable = false;
          log.warn("Could not continue writing process log {}", outputFile, writeFailure);
        }
      }
      if (outputWritable && truncated) {
        output.write(TRUNCATION_MARKER);
      }
    } catch (IOException readOrCloseFailure) {
      // A pipe read failure is expected during forced termination; a regular process completion
      // should close cleanly, so retain a warning without risking a blocked child process.
      log.debug(
          "Process output drain closed with an I/O error for {}", outputFile, readOrCloseFailure);
    }
  }
}
