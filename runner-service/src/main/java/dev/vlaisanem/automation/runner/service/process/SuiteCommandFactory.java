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
 * the REST API never accepts a task name, tag, or shell argument directly. {@code --rerun} is
 * required: without it Gradle's build cache could skip JUnit execution entirely, and the listener
 * would emit zero events for a run that reports as having happened. {@code --no-daemon} is also
 * required: a daemon build hands work off to a long-lived JVM that {@link
 * GradleProcessRunner#terminate} killing our process tree would not reliably reach, so a
 * cancelled/timed-out run's tests could keep executing regardless.
 */
public final class SuiteCommandFactory {

  private SuiteCommandFactory() {}

  public static List<String> commandFor(
      Environment environment,
      Suite suite,
      Path repoRoot,
      String runId,
      Path rawEventsDir,
      Path allureResultsDir,
      List<SelectedTestSnapshot> selectedTests) {
    String task =
        RunCatalog.gradleTaskFor(environment, suite)
            .orElseThrow(
                () ->
                    // RunRequestValidator should already reject an unmapped combination - reaching
                    // here means RunCatalog has a gap, a programming error, not bad input.
                    new IllegalStateException(
                        "No Gradle task mapped for " + environment + " + " + suite));
    List<String> command = new ArrayList<>();
    command.add(gradlewPath(repoRoot));
    command.add(task);
    command.add("--rerun");
    command.add("--no-daemon");
    // The only place a client-submitted value reaches this command line, and only an already
    // catalog-validated testKey (see RunService#submit) - never a raw request-body string.
    for (SelectedTestSnapshot selected : selectedTests) {
      command.add("--tests");
      command.add(selected.testKey().replace('#', '.'));
    }
    command.add("-Drunner.runId=" + runId);
    command.add("-Drunner.rawEventsDir=" + rawEventsDir);
    // Read by build.gradle itself (the outer build JVM), not the forked JUnit worker - a system
    // property doesn't propagate there on its own, unlike an env var. build.gradle uses this to
    // redirect the Allure plugin's per-test result output into this run's own artifacts
    // subdirectory (so it's covered by DiskUsageService/retention) instead of the shared,
    // unbounded build/allure-results directory.
    command.add("-Drunner.allureResultsDir=" + allureResultsDir);
    return List.copyOf(command);
  }

  private static String gradlewPath(Path repoRoot) {
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    String wrapperName = windows ? "gradlew.bat" : "gradlew";
    return repoRoot.resolve(wrapperName).toAbsolutePath().toString();
  }
}
