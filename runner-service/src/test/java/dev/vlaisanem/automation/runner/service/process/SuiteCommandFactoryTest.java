package dev.vlaisanem.automation.runner.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vlaisanem.automation.runner.service.catalog.RunCatalog;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.domain.TestLayer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SuiteCommandFactoryTest {

  private static final Path ALLURE_RESULTS_DIR =
      Path.of("/repo/build/runner-artifacts/run-1/allure-results");

  private static final Map<RunCatalog.Key, String> EXPECTED_TASK =
      Map.of(
          new RunCatalog.Key(Environment.PUBLIC, Suite.SMOKE), "smokeTest",
          new RunCatalog.Key(Environment.PUBLIC, Suite.API), "apiTest",
          new RunCatalog.Key(Environment.PUBLIC, Suite.UI), "uiTest",
          new RunCatalog.Key(Environment.PUBLIC, Suite.JOURNEY), "journeyTest",
          new RunCatalog.Key(Environment.PUBLIC, Suite.REGRESSION), "regressionTest",
          new RunCatalog.Key(Environment.PUBLIC, Suite.FIXTURE), "fixtureTest",
          new RunCatalog.Key(Environment.PUBLIC, Suite.CUSTOM), "customTest");

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
                  Path.of("/repo/build/runner-events"),
                  ALLURE_RESULTS_DIR,
                  List.of());
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
            Path.of("/repo/build/runner-events"),
            ALLURE_RESULTS_DIR,
            List.of());

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
                    Path.of("/repo/build/runner-events"),
                    ALLURE_RESULTS_DIR,
                    List.of()))
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
            Path.of("/repo/build/runner-events"),
            ALLURE_RESULTS_DIR,
            List.of());

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
            Path.of("/repo/build/runner-events"),
            ALLURE_RESULTS_DIR,
            List.of());

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
            Path.of("/repo/build/runner-events"),
            ALLURE_RESULTS_DIR,
            List.of());

    assertThat(command).contains("-Drunner.runId=run-1");
    assertThat(command).contains("-Drunner.rawEventsDir=" + Path.of("/repo/build/runner-events"));
  }

  /**
   * D4.2 review round 3 - this is deliberately a system property, not an environment variable: it
   * must reach {@code build.gradle}'s own configuration code (the outer, {@code --no-daemon} build
   * JVM this command line actually starts), which uses it to override the Allure Gradle plugin's
   * own {@code adapter.resultsDir} - see {@code SuiteCommandFactory}'s own Javadoc for why the raw
   * event byte limit is threaded completely differently (an environment variable, since it must
   * reach {@code RunnerEventWriterRegistry} inside the forked JUnit test-worker JVM itself, which a
   * system property set here never reaches on its own).
   */
  @Test
  void forwardsAllureResultsDirectoryAsASystemProperty() {
    List<String> command =
        SuiteCommandFactory.commandFor(
            Environment.PUBLIC,
            Suite.SMOKE,
            Path.of("/repo"),
            "run-1",
            Path.of("/repo/build/runner-events"),
            ALLURE_RESULTS_DIR,
            List.of());

    assertThat(command).contains("-Drunner.allureResultsDir=" + ALLURE_RESULTS_DIR);
  }

  @Test
  void resolvesGradlewWrapperAsAnAbsolutePathUnderTheRepoRoot() {
    List<String> command =
        SuiteCommandFactory.commandFor(
            Environment.PUBLIC,
            Suite.SMOKE,
            Path.of("/repo"),
            "run-1",
            Path.of("/repo/build/runner-events"),
            ALLURE_RESULTS_DIR,
            List.of());

    String gradlew = command.get(0);
    assertThat(Path.of(gradlew).isAbsolute()).isTrue();
    assertThat(gradlew)
        .satisfiesAnyOf(
            path -> assertThat(path).endsWith("gradlew.bat"),
            path -> assertThat(path).endsWith("gradlew"));
  }

  @Test
  void nonCustomSuitesNeverAddATestsFlagEvenWhenSelectedTestsIsEmpty() {
    List<String> command =
        SuiteCommandFactory.commandFor(
            Environment.PUBLIC,
            Suite.SMOKE,
            Path.of("/repo"),
            "run-1",
            Path.of("/repo/build/runner-events"),
            ALLURE_RESULTS_DIR,
            List.of());

    assertThat(command).doesNotContain("--tests");
  }

  @Test
  void customAppendsOneTestsFlagPerSelectedTestTranslatingHashToDot() {
    List<SelectedTestSnapshot> selected =
        List.of(
            new SelectedTestSnapshot(
                "dev.vlaisanem.automation.tests.api.AuthenticationApiTest#adminCanAuthenticate",
                "Admin can obtain a non-empty session token",
                TestLayer.API),
            new SelectedTestSnapshot(
                "dev.vlaisanem.automation.tests.ui.HomePageTest#guestCanDiscoverBookableRooms",
                "Guest can see at least one bookable room",
                TestLayer.UI));

    List<String> command =
        SuiteCommandFactory.commandFor(
            Environment.PUBLIC,
            Suite.CUSTOM,
            Path.of("/repo"),
            "run-1",
            Path.of("/repo/build/runner-events"),
            ALLURE_RESULTS_DIR,
            selected);

    assertThat(command)
        .containsSubsequence(
            "--tests",
            "dev.vlaisanem.automation.tests.api.AuthenticationApiTest.adminCanAuthenticate",
            "--tests",
            "dev.vlaisanem.automation.tests.ui.HomePageTest.guestCanDiscoverBookableRooms");
  }
}
