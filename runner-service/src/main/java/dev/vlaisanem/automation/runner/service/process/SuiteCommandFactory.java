package dev.vlaisanem.automation.runner.service.process;

import dev.vlaisanem.automation.runner.service.catalog.RunCatalog;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Maps an allowlisted (environment, suite) pair to a fixed Gradle command via {@link RunCatalog} -
 * the REST API never accepts a task name, tag, or shell argument directly (see {@code
 * RunRequestValidator}, which callers are expected to have already run). {@code --rerun} is
 * required, not optional: without it Gradle's build cache could return a cached result with no
 * JUnit execution at all, meaning the listener would emit zero events for a run that reports as
 * having happened.
 *
 * <p>{@code --no-daemon} is also required, not optional: a daemon build hands the real work off to
 * a long-lived background JVM that outlives the client process and can even be reused across
 * unrelated invocations - {@link GradleProcessRunner#terminate} killing our process tree would not
 * reliably reach it, so a cancelled or timed-out run's tests could keep executing regardless.
 */
public final class SuiteCommandFactory {

  private SuiteCommandFactory() {}

  public static List<String> commandFor(
      Environment environment,
      Suite suite,
      Path repoRoot,
      String runId,
      Path rawEventsDir,
      List<SelectedTestSnapshot> selectedTests) {
    String task =
        RunCatalog.gradleTaskFor(environment, suite)
            .orElseThrow(
                () ->
                    // RunRequestValidator should already have rejected an unmapped combination -
                    // reaching here means RunCatalog has a gap between what it allows and what it
                    // can actually map, a programming error, not bad input.
                    new IllegalStateException(
                        "No Gradle task mapped for " + environment + " + " + suite));
    List<String> command = new ArrayList<>();
    command.add(gradlewPath(repoRoot));
    command.add(task);
    command.add("--rerun");
    command.add("--no-daemon");
    // The only place a client-submitted value ever reaches this command line - and only the exact
    // testKey a caller already validated against the server's own catalog (see
    // RunService#submit), translated into Gradle's `--tests Class.method` filter syntax. Never a
    // raw string from the request body copied through directly.
    for (SelectedTestSnapshot selected : selectedTests) {
      command.add("--tests");
      command.add(selected.testKey().replace('#', '.'));
    }
    command.add("-Drunner.runId=" + runId);
    command.add("-Drunner.rawEventsDir=" + rawEventsDir);
    return List.copyOf(command);
  }

  private static String gradlewPath(Path repoRoot) {
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    String wrapperName = windows ? "gradlew.bat" : "gradlew";
    return repoRoot.resolve(wrapperName).toAbsolutePath().toString();
  }
}
