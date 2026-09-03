package dev.vlaisanem.automation.runner.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vlaisanem.automation.runner.service.catalog.RunCatalog;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SuiteCommandFactoryTest {

  private static final Map<RunCatalog.Key, String> EXPECTED_TASK =
      Map.of(
          new RunCatalog.Key(Environment.PUBLIC, Suite.SMOKE), "smokeTest",
          new RunCatalog.Key(Environment.PUBLIC, Suite.API), "apiTest",
          new RunCatalog.Key(Environment.PUBLIC, Suite.UI), "uiTest",
          new RunCatalog.Key(Environment.PUBLIC, Suite.JOURNEY), "journeyTest",
          new RunCatalog.Key(Environment.PUBLIC, Suite.REGRESSION), "regressionTest",
          new RunCatalog.Key(Environment.PUBLIC, Suite.FIXTURE), "fixtureTest");

  @Test
  void mapsEveryPublicSuiteToItsDedicatedGradleTask() {
    EXPECTED_TASK.forEach(
        (key, task) -> {
          List<String> command =
              SuiteCommandFactory.commandFor(
                  key.environment(),
                  key.suite(),
                  Path.of("/repo"),
                  "run-1",
                  Path.of("/repo/build/runner-events"));
          assertThat(command).contains(task);
        });
  }

  @Test
  void mapsLocalJourneyToItsOwnDedicatedGradleTask() {
    List<String> command =
        SuiteCommandFactory.commandFor(
            Environment.LOCAL,
            Suite.JOURNEY,
            Path.of("/repo"),
            "run-1",
            Path.of("/repo/build/runner-events"));

    assertThat(command).contains("localJourneyTest");
  }

  @Test
  void rejectsACombinationRunCatalogDoesNotMap() {
    assertThatThrownBy(
            () ->
                SuiteCommandFactory.commandFor(
                    Environment.LOCAL,
                    Suite.SMOKE,
                    Path.of("/repo"),
                    "run-1",
                    Path.of("/repo/build/runner-events")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOCAL")
        .hasMessageContaining("SMOKE");
  }

  @Test
  void alwaysIncludesRerunToBypassTheGradleBuildCache() {
    List<String> command =
        SuiteCommandFactory.commandFor(
            Environment.PUBLIC,
            Suite.SMOKE,
            Path.of("/repo"),
            "run-1",
            Path.of("/repo/build/runner-events"));

    assertThat(command).contains("--rerun");
  }

  @Test
  void alwaysIncludesNoDaemonSoTerminationCanReachTheRealWork() {
    List<String> command =
        SuiteCommandFactory.commandFor(
            Environment.PUBLIC,
            Suite.SMOKE,
            Path.of("/repo"),
            "run-1",
            Path.of("/repo/build/runner-events"));

    assertThat(command).contains("--no-daemon");
  }

  @Test
  void forwardsRunIdAndRawEventsDirAsSystemProperties() {
    List<String> command =
        SuiteCommandFactory.commandFor(
            Environment.PUBLIC,
            Suite.SMOKE,
            Path.of("/repo"),
            "run-1",
            Path.of("/repo/build/runner-events"));

    assertThat(command).contains("-Drunner.runId=run-1");
    assertThat(command).contains("-Drunner.rawEventsDir=" + Path.of("/repo/build/runner-events"));
  }

  @Test
  void resolvesGradlewWrapperAsAnAbsolutePathUnderTheRepoRoot() {
    List<String> command =
        SuiteCommandFactory.commandFor(
            Environment.PUBLIC,
            Suite.SMOKE,
            Path.of("/repo"),
            "run-1",
            Path.of("/repo/build/runner-events"));

    String gradlew = command.get(0);
    assertThat(Path.of(gradlew).isAbsolute()).isTrue();
    assertThat(gradlew)
        .satisfiesAnyOf(
            path -> assertThat(path).endsWith("gradlew.bat"),
            path -> assertThat(path).endsWith("gradlew"));
  }
}
