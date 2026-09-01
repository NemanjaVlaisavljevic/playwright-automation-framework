package dev.vlaisanem.automation.runner.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.exception.ProcessTerminationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

/**
 * Exercises {@link GradleProcessRunner} against a real OS process (see {@link SleepAndExitFixture})
 * rather than mocking {@link Process} - start/wait/forced-kill behavior is exactly the part worth
 * verifying against the real JDK process APIs.
 */
class GradleProcessRunnerTest {

  @Test
  void completesNormallyWithTheChildsExitCode(@TempDir Path tempDir) throws IOException {
    GradleProcessRunner runner = newRunner(Duration.ofSeconds(5), 1024 * 1024);
    Path outputFile = tempDir.resolve("run.log");
    Process process = runner.start(sleepAndExitCommand(0, 7), tempDir, outputFile);

    ProcessOutcome outcome = runner.awaitCompletion(process, Duration.ofSeconds(30));

    assertThat(outcome.kind()).isEqualTo(ProcessOutcome.Kind.COMPLETED);
    assertThat(outcome.exitCode()).isEqualTo(7);
    assertThat(process.isAlive()).isFalse();
    assertThat(outputFile).content().contains("fixture stdout").contains("fixture stderr");
  }

  @Test
  void killsAndReportsTimedOutWhenTheDeadlinePasses(@TempDir Path tempDir) throws IOException {
    GradleProcessRunner runner = newRunner(Duration.ofSeconds(5), 1024 * 1024);
    Process process =
        runner.start(sleepAndExitCommand(10_000, 0), tempDir, tempDir.resolve("run.log"));

    ProcessOutcome outcome = runner.awaitCompletion(process, Duration.ofMillis(200));

    assertThat(outcome.kind()).isEqualTo(ProcessOutcome.Kind.TIMED_OUT);
    assertThat(process.isAlive()).isFalse();
  }

  @Test
  void doesNotBlockOnTheChildsOutputPipe(@TempDir Path tempDir) throws IOException {
    GradleProcessRunner runner = newRunner(Duration.ofSeconds(5), 1024);
    Path outputFile = tempDir.resolve("chatty.log");
    Process process = runner.start(sleepAndExitCommand(0, 0, 100_000), tempDir, outputFile);

    ProcessOutcome outcome = runner.awaitCompletion(process, Duration.ofSeconds(30));

    assertThat(outcome.kind()).isEqualTo(ProcessOutcome.Kind.COMPLETED);
    assertThat(Files.size(outputFile)).isLessThanOrEqualTo(1024);
    assertThat(outputFile).content().contains("process log truncated");
  }

  /**
   * Regression test for the review's exact finding: {@code destroyForcibly()} on the direct process
   * handle alone does not reach a grandchild - a real gap for {@code gradlew.bat}, whose direct
   * process is a wrapper script while the actual Gradle/JUnit work happens in a descendant.
   */
  @Test
  void terminateKillsTheEntireProcessTreeIncludingGrandchildren(@TempDir Path tempDir)
      throws IOException, InterruptedException {
    GradleProcessRunner runner = newRunner(Duration.ofSeconds(5), 1024 * 1024);
    Path pidFile = tempDir.resolve("child.pid");
    Process process =
        runner.start(processTreeCommand(pidFile), tempDir, tempDir.resolve("tree.log"));

    long childPid = awaitPidFile(pidFile);
    ProcessHandle childHandle = ProcessHandle.of(childPid).orElseThrow();
    assertThat(childHandle.isAlive()).isTrue();

    runner.terminate(process);

    assertThat(process.isAlive()).isFalse();
    assertThat(childHandle.isAlive()).isFalse();
  }

  @Test
  void terminateFailsLoudlyWhenAProcessSurvivesForcedTermination() {
    GradleProcessRunner runner = newRunner(Duration.ofMillis(1), 1024);
    Process process = mock(Process.class);
    ProcessHandle descendant = mock(ProcessHandle.class);
    // A Stream can only be consumed once, but terminate() calls descendants() repeatedly (entry,
    // then again on each forced-kill pass) - thenAnswer hands back a fresh one each time.
    when(process.descendants()).thenAnswer(invocation -> Stream.of(descendant));
    when(process.isAlive()).thenReturn(true);
    when(process.pid()).thenReturn(10L);
    when(descendant.isAlive()).thenReturn(true);
    when(descendant.pid()).thenReturn(11L);
    when(descendant.descendants()).thenAnswer(invocation -> Stream.empty());

    assertThatThrownBy(() -> runner.terminate(process))
        .isInstanceOf(ProcessTerminationException.class)
        .hasMessageContaining("10")
        .hasMessageContaining("11");
  }

  @Test
  void terminateSignalsKnownDescendantsBeforeTheirRootProcess() {
    GradleProcessRunner runner = newRunner(Duration.ofSeconds(1), 1024);
    Process process = mock(Process.class);
    ProcessHandle descendant = mock(ProcessHandle.class);
    AtomicBoolean processAlive = new AtomicBoolean(true);
    AtomicBoolean descendantAlive = new AtomicBoolean(true);
    when(process.descendants()).thenAnswer(invocation -> Stream.of(descendant));
    when(process.isAlive()).thenAnswer(invocation -> processAlive.get());
    when(descendant.isAlive()).thenAnswer(invocation -> descendantAlive.get());
    when(descendant.pid()).thenReturn(11L);
    when(descendant.descendants()).thenAnswer(invocation -> Stream.empty());
    doAnswer(
            invocation -> {
              descendantAlive.set(false);
              return true;
            })
        .when(descendant)
        .destroy();
    doAnswer(
            invocation -> {
              processAlive.set(false);
              return null;
            })
        .when(process)
        .destroy();

    runner.terminate(process);

    InOrder shutdownOrder = inOrder(descendant, process);
    shutdownOrder.verify(descendant).destroy();
    shutdownOrder.verify(process).destroy();
  }

  /**
   * Regression test for the review's finding: a single snapshot (even taken twice) would never
   * learn about a child spawned during the graceful-shutdown wait. {@code terminate()} now repeats
   * discovery on every forced-kill pass, so a descendant that only appears from the second call
   * onward must still be targeted.
   */
  @Test
  void terminateAlsoTargetsADescendantThatAppearsOnlyAfterTheInitialSnapshot() {
    GradleProcessRunner runner = newRunner(Duration.ofMillis(1), 1024);
    Process process = mock(Process.class);
    ProcessHandle lateDescendant = mock(ProcessHandle.class);
    AtomicInteger callCount = new AtomicInteger();
    // Empty on the very first (entry) discovery; every call from the second onward reveals the
    // descendant, simulating one spawned during the graceful-shutdown wait. A counter-driven
    // answer (rather than a fixed thenReturn list) guarantees a fresh Stream on every call,
    // however many discovery passes terminate() ends up making.
    when(process.descendants())
        .thenAnswer(
            invocation ->
                callCount.incrementAndGet() == 1
                    ? Stream.<ProcessHandle>empty()
                    : Stream.of(lateDescendant));
    when(process.isAlive()).thenReturn(true);
    when(process.pid()).thenReturn(10L);
    when(lateDescendant.isAlive()).thenReturn(true);
    when(lateDescendant.pid()).thenReturn(22L);
    when(lateDescendant.descendants()).thenAnswer(invocation -> Stream.empty());

    assertThatThrownBy(() -> runner.terminate(process))
        .isInstanceOf(ProcessTerminationException.class)
        .hasMessageContaining("22");

    // It stays "alive" for every mocked check, so the retry loop targets it on each pass -
    // atLeastOnce (not exactly once) is what actually matters: it was targeted at all.
    verify(lateDescendant, atLeastOnce()).destroyForcibly();
  }

  /**
   * Regression test for the review's finding: {@code process.descendants()} alone can miss a
   * grandchild once its immediate parent dies and it gets reparented away from the root's view.
   * {@code terminate()} also scans every still-alive already-known handle's own {@code
   * descendants()}, which is the only way this grandchild (never reachable through {@code
   * process.descendants()} at all) can still be discovered and killed.
   */
  @Test
  void terminateAlsoTargetsAGrandchildOnlyDiscoverableThroughAKnownHandlesOwnDescendants() {
    GradleProcessRunner runner = newRunner(Duration.ofMillis(1), 1024);
    Process process = mock(Process.class);
    ProcessHandle child = mock(ProcessHandle.class);
    ProcessHandle grandchild = mock(ProcessHandle.class);
    when(process.descendants()).thenAnswer(invocation -> Stream.of(child));
    when(child.descendants()).thenAnswer(invocation -> Stream.of(grandchild));
    when(grandchild.descendants()).thenAnswer(invocation -> Stream.empty());
    when(process.isAlive()).thenReturn(true);
    when(process.pid()).thenReturn(10L);
    when(child.isAlive()).thenReturn(true);
    when(child.pid()).thenReturn(11L);
    when(grandchild.isAlive()).thenReturn(true);
    when(grandchild.pid()).thenReturn(12L);

    assertThatThrownBy(() -> runner.terminate(process))
        .isInstanceOf(ProcessTerminationException.class)
        .hasMessageContaining("12");

    verify(grandchild, atLeastOnce()).destroyForcibly();
  }

  /**
   * Regression test for the review's finding: {@code allDeadWithin} used to check only the {@code
   * known} snapshot captured before the wait started, never re-discovering while polling - a
   * descendant that only appears mid-wait (not at a pass boundary) would be missed entirely and,
   * under the old code, this exact scenario would exhaust every forced-kill pass and throw. Now
   * discovery (and the matching kill signal) repeats on every poll tick of the wait itself, so this
   * resolves successfully within the graceful phase, never needing a forced pass at all.
   */
  @Test
  void terminateRediscoversAndKillsADescendantThatAppearsOnlyDuringTheWaitNotAtAPassBoundary() {
    GradleProcessRunner runner = newRunner(Duration.ofSeconds(2), 1024);
    Process process = mock(Process.class);
    ProcessHandle midWaitDescendant = mock(ProcessHandle.class);
    AtomicInteger discoveryCalls = new AtomicInteger();

    // Root discovery is called exactly once per allDeadWithin loop iteration (at its top) - this
    // counter therefore doubles as an iteration count. Empty for the first two iterations (nothing
    // to find yet), reveals the descendant only from the third iteration onward, and everything is
    // reported dead from the fifth iteration onward - modelling a process that genuinely appears
    // partway through the grace-period wait, not at its start.
    when(process.descendants())
        .thenAnswer(
            invocation ->
                discoveryCalls.incrementAndGet() <= 2
                    ? Stream.<ProcessHandle>empty()
                    : Stream.of(midWaitDescendant));
    when(process.isAlive()).thenAnswer(invocation -> discoveryCalls.get() < 5);
    when(process.pid()).thenReturn(10L);
    when(midWaitDescendant.isAlive()).thenAnswer(invocation -> discoveryCalls.get() < 5);
    when(midWaitDescendant.pid()).thenReturn(33L);
    when(midWaitDescendant.descendants()).thenAnswer(invocation -> Stream.empty());

    runner.terminate(process);

    assertThat(discoveryCalls.get()).isGreaterThan(2);
    verify(midWaitDescendant, atLeastOnce()).destroy();
    verify(midWaitDescendant, never()).destroyForcibly();
  }

  private List<String> processTreeCommand(Path pidFile) {
    return List.of(
        javaExecutable(),
        "-cp",
        System.getProperty("java.class.path"),
        ProcessTreeFixture.class.getName(),
        pidFile.toString());
  }

  private long awaitPidFile(Path pidFile) throws InterruptedException {
    Instant deadline = Instant.now().plusSeconds(10);
    while (Instant.now().isBefore(deadline)) {
      if (Files.exists(pidFile)) {
        try {
          String content = Files.readString(pidFile).trim();
          if (!content.isEmpty()) {
            return Long.parseLong(content);
          }
        } catch (IOException ignored) {
          // File may still be mid-write - retry.
        }
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Child PID file was never written: " + pidFile);
  }

  private List<String> sleepAndExitCommand(long sleepMillis, int exitCode) {
    return List.of(
        javaExecutable(),
        "-cp",
        System.getProperty("java.class.path"),
        SleepAndExitFixture.class.getName(),
        Long.toString(sleepMillis),
        Integer.toString(exitCode));
  }

  private List<String> sleepAndExitCommand(long sleepMillis, int exitCode, int outputBytes) {
    return List.of(
        javaExecutable(),
        "-cp",
        System.getProperty("java.class.path"),
        SleepAndExitFixture.class.getName(),
        Long.toString(sleepMillis),
        Integer.toString(exitCode),
        Integer.toString(outputBytes));
  }

  private GradleProcessRunner newRunner(Duration terminationGracePeriod, long maxLogBytes) {
    RunnerProperties properties =
        new RunnerProperties(
            ".",
            Duration.ofSeconds(30),
            "build/events/raw",
            "build/events/journal",
            "build/logs",
            maxLogBytes,
            terminationGracePeriod,
            Duration.ofSeconds(1),
            1,
            Duration.ofMillis(150),
            Duration.ofSeconds(5),
            10_000,
            Duration.ofSeconds(15),
            Duration.ofMinutes(10));
    return new GradleProcessRunner(properties);
  }

  private String javaExecutable() {
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    String javaHome = System.getProperty("java.home");
    return javaHome + "/bin/java" + (windows ? ".exe" : "");
  }
}
