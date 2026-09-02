package dev.vlaisanem.automation.dashboarde2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * A managed OS subprocess this suite starts (the {@code runner-service} jar, or the dashboard's
 * Vite dev/preview server) and is responsible for health-polling until ready and stopping again. No
 * fixed blind startup delay - the same convention {@code localSutHealth} already uses in the root
 * {@code build.gradle} for the local Restful Booker Platform stack: poll on a short interval
 * against a deadline, and fail fast (with the process's own log attached to the exception message)
 * if it exits before ever becoming healthy.
 */
final class DashboardProcess {

  private final String name;
  private final Process process;
  private final Path logFile;

  private DashboardProcess(String name, Process process, Path logFile) {
    this.name = name;
    this.process = process;
    this.logFile = logFile;
  }

  /**
   * Starts {@code command} in {@code workingDir}, waits for {@code healthUrl} to return HTTP 200
   * twice in a row (polling every 500ms, up to {@code timeout}), and returns once it does. Refuses
   * to even launch if something is already answering {@code healthUrl} beforehand - see {@link
   * #refuseIfAlreadyAnswering}. stdout/stderr are merged and appended to {@code
   * build/dashboard-e2e-logs/<name>.log} for post-mortem diagnosis - never inherited directly (a
   * child process inheriting console handles has previously been observed to hang indefinitely on
   * Windows, see the root build.gradle's own note on this for {@code docker compose up}).
   */
  static DashboardProcess start(
      String name,
      List<String> command,
      Path workingDir,
      Map<String, String> extraEnv,
      String healthUrl,
      Duration healthTimeout)
      throws IOException {
    refuseIfAlreadyAnswering(name, healthUrl);

    Path logFile = logFileFor(name);
    Files.createDirectories(logFile.getParent());

    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(workingDir.toFile());
    builder.environment().putAll(extraEnv);
    builder.redirectErrorStream(true);
    builder.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));

    Process process = builder.start();
    DashboardProcess managed = new DashboardProcess(name, process, logFile);
    try {
      managed.waitForHealthy(healthUrl, healthTimeout);
    } catch (RuntimeException e) {
      // The process itself did start, even though it never became healthy - stop it here rather
      // than leaving that decision to the caller, which never gets a `DashboardProcess` reference
      // back to close (this call never returns one, it throws) and would otherwise leak the OS
      // process for the rest of the JVM's life. Observed live: an earlier version of this method
      // leaked exactly this way, and the orphaned process then held the port for every subsequent
      // run until killed by hand.
      try {
        managed.stop();
      } catch (RuntimeException stopFailure) {
        // The health-check failure is the actionable cause here - a process that also resists
        // termination is a second, separate problem, not one that should replace or hide the
        // first in whatever this method's caller ends up reporting.
        e.addSuppressed(stopFailure);
      }
      throw e;
    }
    return managed;
  }

  private static Path logFileFor(String name) {
    return Path.of("build", "dashboard-e2e-logs", name + ".log");
  }

  /**
   * A stale process from an earlier run - or any other unrelated service - already bound to {@code
   * healthUrl}'s port would otherwise be indistinguishable from {@code name} becoming healthy the
   * moment {@link #waitForHealthy} starts polling, before the process this method is about to
   * launch has even had a chance to bind that port itself (successfully or not). Observed live in
   * this repo's own history: a forgotten manual run left the exact backend/dashboard ports
   * occupied, and the health poll happily accepted the leftover process as the freshly started
   * instance. Refusing to even launch when something is already answering closes that window at its
   * source, rather than trying to distinguish "old" from "new" after the fact.
   */
  private static void refuseIfAlreadyAnswering(String name, String healthUrl) {
    boolean alreadyHealthy;
    try {
      alreadyHealthy = httpGet(healthUrl) == 200;
    } catch (IOException e) {
      // Could not connect at all - exactly the expected, healthy case before anything has been
      // launched yet, so there is nothing to refuse.
      return;
    }
    if (alreadyHealthy) {
      throw new IllegalStateException(
          "Refusing to start "
              + name
              + ": something is already answering HTTP 200 at "
              + healthUrl
              + " before this process was even launched - stop whatever is bound to that port"
              + " first.");
    }
  }

  /**
   * Deliberately {@link HttpURLConnection}, not {@code java.net.http.HttpClient}: the modern client
   * was observed live to hang past its own per-request timeout against a real local Vite server in
   * this environment (backend/Tomcat connections through it were fine; Vite's dev server
   * connections consistently were not, for reasons never fully root-caused), while {@code
   * HttpURLConnection} - the same mechanism the root {@code build.gradle}'s own {@code
   * localSutHealth} task already uses successfully for this exact kind of local health-polling -
   * works reliably.
   *
   * <p>Requires two consecutive HTTP 200 responses, re-checking {@link Process#isAlive()}
   * immediately after each one (not just before the request, as a single check before the request
   * would) - a process that dies between the request being sent and its response being read (e.g.
   * because the port it tried to bind was already taken) could otherwise have its very last gasp,
   * or even a second stale process's response, mistaken for having become healthy.
   */
  private void waitForHealthy(String healthUrl, Duration timeout) {
    Instant deadline = Instant.now().plus(timeout);
    Exception lastFailure = null;
    int consecutiveHealthy = 0;
    while (Instant.now().isBefore(deadline)) {
      if (!process.isAlive()) {
        throw new IllegalStateException(
            name
                + " exited before becoming healthy (exit code "
                + process.exitValue()
                + ") - see "
                + logFile);
      }
      try {
        boolean healthyAndStillAlive = httpGet(healthUrl) == 200 && process.isAlive();
        if (healthyAndStillAlive) {
          consecutiveHealthy++;
          if (consecutiveHealthy >= 2) {
            return;
          }
        } else {
          consecutiveHealthy = 0;
        }
      } catch (IOException e) {
        lastFailure = e;
        consecutiveHealthy = 0;
      }
      sleep(Duration.ofMillis(500));
    }
    throw new IllegalStateException(
        name + " did not become healthy within " + timeout + " (see " + logFile + ")", lastFailure);
  }

  private static int httpGet(String healthUrl) throws IOException {
    HttpURLConnection connection =
        (HttpURLConnection) URI.create(healthUrl).toURL().openConnection();
    connection.setConnectTimeout(2000);
    connection.setReadTimeout(2000);
    connection.setRequestMethod("GET");
    int status = connection.getResponseCode();
    connection.disconnect();
    return status;
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for process health", e);
    }
  }

  /**
   * Graceful stop first (SIGTERM-equivalent), forcible after a grace period - of the whole process
   * tree, not just the direct child. Observed live: {@code npm run dev}/{@code preview} is launched
   * on Windows as {@code cmd.exe /c npm.cmd ...}, and {@code npm.cmd} itself spawns the real {@code
   * node.exe} running Vite as a grandchild - destroying only the direct {@link Process} handle (a
   * plain {@code process.destroy()}) kills {@code cmd.exe} but leaves that grandchild running,
   * orphaned and still holding the port, for the rest of the machine's uptime.
   *
   * <p>Mirrors {@code GradleProcessRunner#terminate}'s own iterative collect-and-kill pattern
   * rather than a single before/after snapshot, for the same reason documented there: a single
   * snapshot (even taken twice) cannot fully protect against a descendant that spawns its own child
   * moments before dying, and waiting only on {@code process} itself (this method's own earlier
   * version) means a quick-exiting {@code cmd.exe} parent can report {@code exited} while its
   * {@code node.exe} grandchild is still very much alive and still holding the port - discovery,
   * the kill signal, and the liveness check that decides success all repeat on every poll tick, not
   * just once per pass.
   */
  void stop() {
    Map<Long, ProcessHandle> known = new LinkedHashMap<>();

    if (allDeadWithin(known, Duration.ofSeconds(10), ProcessHandle::destroy)) {
      return;
    }
    if (allDeadWithin(known, Duration.ofSeconds(10), ProcessHandle::destroyForcibly)) {
      return;
    }
    // `known` only ever holds descendants (see discoverNewDescendants) - a process with no
    // children at all (e.g. the backend's own plain `java -jar`, launched with no shell wrapper)
    // would otherwise report an empty, misleadingly reassuring survivor list here even though the
    // root process itself is the one still alive and still holding its port.
    List<Long> survivors =
        Stream.concat(Stream.of(process.toHandle()), known.values().stream())
            .filter(ProcessHandle::isAlive)
            .map(ProcessHandle::pid)
            .distinct()
            .toList();
    if (!survivors.isEmpty()) {
      // Deliberately thrown, not just logged: a caller relying on this method's return to mean
      // "the port is free now" (see BackendUnavailableE2eTest.startBackendOnIsolatedPort, which
      // restarts on the exact same port right after stopping the previous instance) must not be
      // able to silently proceed while the old process is still bound to it.
      throw new IllegalStateException(
          name + ": processes survived termination: " + survivors + " (see " + logFile + ")");
    }
  }

  private boolean allDeadWithin(
      Map<Long, ProcessHandle> known, Duration timeout, Consumer<ProcessHandle> kill) {
    ProcessHandle root = process.toHandle();
    Instant deadline = Instant.now().plus(timeout);
    while (true) {
      discoverNewDescendants(root, known);
      known.values().stream().filter(ProcessHandle::isAlive).forEach(kill);
      kill.accept(root);
      if (!root.isAlive() && known.values().stream().noneMatch(ProcessHandle::isAlive)) {
        return true;
      }
      if (!Instant.now().isBefore(deadline)) {
        return false;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
  }

  /**
   * Adds any not-yet-known descendant of {@code root}, or of any still-alive already-known handle,
   * into {@code known} (keyed by PID to dedupe) - scanning every known-alive handle's own
   * descendants too, not just the root's, is what can still catch a grandchild after its immediate
   * parent has already been reparented away from {@code root}'s own view.
   */
  private static void discoverNewDescendants(ProcessHandle root, Map<Long, ProcessHandle> known) {
    root.descendants().forEach(handle -> known.putIfAbsent(handle.pid(), handle));
    List.copyOf(known.values()).stream()
        .filter(ProcessHandle::isAlive)
        .forEach(handle -> handle.descendants().forEach(d -> known.putIfAbsent(d.pid(), d)));
  }

  boolean isAlive() {
    return process.isAlive();
  }

  Path logFile() {
    return logFile;
  }

  static String readLogTail(Path logFile, int maxLines) {
    try {
      List<String> lines = Files.readAllLines(logFile);
      int from = Math.max(0, lines.size() - maxLines);
      return String.join("\n", lines.subList(from, lines.size()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
