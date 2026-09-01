package dev.vlaisanem.automation.runner.service.process;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vlaisanem.automation.runner.service.domain.Suite;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SuiteCommandFactoryTest {

  private static final Map<Suite, String> EXPECTED_TASK =
      Map.of(
          Suite.SMOKE, "smokeTest",
          Suite.API, "apiTest",
          Suite.UI, "uiTest",
          Suite.JOURNEY, "journeyTest",
          Suite.REGRESSION, "regressionTest");

  @Test
  void mapsEverySuiteToItsDedicatedGradleTask() {
    EXPECTED_TASK.forEach(
        (suite, task) -> {
          List<String> command =
              SuiteCommandFactory.commandFor(
                  suite, Path.of("/repo"), "run-1", Path.of("/repo/build/runner-events"));
          assertThat(command).contains(task);
        });
  }

  @Test
  void alwaysIncludesRerunToBypassTheGradleBuildCache() {
    List<String> command =
        SuiteCommandFactory.commandFor(
            Suite.SMOKE, Path.of("/repo"), "run-1", Path.of("/repo/build/runner-events"));

    assertThat(command).contains("--rerun");
  }

  @Test
  void alwaysIncludesNoDaemonSoTerminationCanReachTheRealWork() {
    List<String> command =
        SuiteCommandFactory.commandFor(
            Suite.SMOKE, Path.of("/repo"), "run-1", Path.of("/repo/build/runner-events"));

    assertThat(command).contains("--no-daemon");
  }

  @Test
  void forwardsRunIdAndRawEventsDirAsSystemProperties() {
    List<String> command =
        SuiteCommandFactory.commandFor(
            Suite.SMOKE, Path.of("/repo"), "run-1", Path.of("/repo/build/runner-events"));

    assertThat(command).contains("-Drunner.runId=run-1");
    assertThat(command).contains("-Drunner.rawEventsDir=" + Path.of("/repo/build/runner-events"));
  }

  @Test
  void resolvesGradlewWrapperAsAnAbsolutePathUnderTheRepoRoot() {
    List<String> command =
        SuiteCommandFactory.commandFor(
            Suite.SMOKE, Path.of("/repo"), "run-1", Path.of("/repo/build/runner-events"));

    String gradlew = command.get(0);
    assertThat(Path.of(gradlew).isAbsolute()).isTrue();
    assertThat(gradlew)
        .satisfiesAnyOf(
            path -> assertThat(path).endsWith("gradlew.bat"),
            path -> assertThat(path).endsWith("gradlew"));
  }
}
