package dev.vlaisanem.automation.runner.service.process;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.domain.TestLayer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D4.2 review round 3 - a real, non-daemon child Gradle invocation (exactly what {@code
 * RunService}/{@code GradleProcessRunner} launch in production, via the same real {@link
 * SuiteCommandFactory#commandFor}), not a unit test against {@code SuiteCommandFactory}'s own
 * output. Two earlier review rounds found that neither the raw-event byte cap nor the Allure
 * results redirection actually reached the forked JUnit test-worker JVM - a config-value-shaped
 * unit test could not have caught either gap, since both are genuinely about what {@code
 * build.gradle}'s own configuration does with a value once a real Gradle build starts executing.
 *
 * <p>Deliberately lives in {@code databaseIntegrationTest}, not the routine {@code test}/CI gate -
 * it spawns a second, real, cold {@code --no-daemon} Gradle process (see {@code fullBackendGate}),
 * which is real infrastructure verification, not a fast unit check.
 *
 * <p>Targets {@link Suite#FIXTURE}'s own {@code fixtureTest} task, narrowed via an explicit {@code
 * --tests} selection to only {@code RawEventOverflowFixtureTest} - {@code fixtureTest} also
 * contains two deliberately failing/blocking siblings never meant to run unattended.
 */
class RawEventAndAllurePropagationRealGradleTest {

  private static final String FIXTURE_TEST_KEY =
      "dev.vlaisanem.automation.tests.fixture.RawEventOverflowFixtureTest#emitsManyStepEvents";

  @Test
  void aRealChildGradleInvocationHonorsTheConfiguredRawEventCapAndAllureResultsDirectory(
      @TempDir Path tempDir) throws IOException, InterruptedException {
    Path repoRoot = Path.of("..").toAbsolutePath().normalize();
    Path rawEventsDir = Files.createDirectories(tempDir.resolve("raw"));
    Path allureResultsDir = tempDir.resolve("allure-results");
    String runId = "d42-propagation-" + UUID.randomUUID();

    Path globalAllureResultsDir = repoRoot.resolve("build").resolve("allure-results");
    Set<Path> globalAllureFilesBefore = listFiles(globalAllureResultsDir);

    List<String> command =
        SuiteCommandFactory.commandFor(
            Environment.PUBLIC,
            Suite.FIXTURE,
            repoRoot,
            runId,
            rawEventsDir,
            allureResultsDir,
            List.of(
                new SelectedTestSnapshot(
                    FIXTURE_TEST_KEY, "Emits many step events", TestLayer.UI)));

    ProcessBuilder builder =
        new ProcessBuilder(command).directory(repoRoot.toFile()).redirectErrorStream(true);
    // Deliberately far below anything RawEventOverflowFixtureTest's own 200 steps could stay
    // under (a real run of it produces ~200 KB - see that class's own Javadoc) - guarantees the
    // overflow path fires reliably, not by chance.
    builder.environment().put("RUNNER_RAW_EVENT_MAX_BYTES", "2048");
    Path gradleOutputLog = tempDir.resolve("gradle-output.log");
    builder.redirectOutput(gradleOutputLog.toFile());
    Process process = builder.start();
    boolean finished = process.waitFor(5, TimeUnit.MINUTES);
    if (!finished) {
      process.destroyForcibly();
    }
    assertThat(finished)
        .as(
            "real child Gradle invocation must finish within the timeout - see %s for its output"
                + " if this fails",
            gradleOutputLog)
        .isTrue();

    Path overflowMarker = rawEventsDir.resolve(runId + ".tests.overflow");
    Path completionMarker = rawEventsDir.resolve(runId + ".tests.complete");
    assertThat(overflowMarker)
        .as(
            "the configured RUNNER_RAW_EVENT_MAX_BYTES env var must have reached"
                + " RunnerEventWriterRegistry inside the real forked test-worker JVM")
        .exists();
    assertThat(completionMarker)
        .as("an overflowed stream must never also produce the normal completion marker")
        .doesNotExist();

    assertThat(listFiles(allureResultsDir))
        .as(
            "the configured runner.allureResultsDir must have reached the Allure Gradle plugin's"
                + " own adapter.resultsDir, redirecting its real per-test result files here")
        .anyMatch(path -> path.getFileName().toString().endsWith("-result.json"));

    Set<Path> globalAllureFilesAfter = listFiles(globalAllureResultsDir);
    assertThat(globalAllureFilesAfter)
        .as(
            "no new Allure result should land in the shared global directory once redirection is"
                + " configured - only pre-existing files (from unrelated invocations) may remain")
        .isSubsetOf(globalAllureFilesBefore);
  }

  private static Set<Path> listFiles(Path dir) throws IOException {
    if (!Files.exists(dir)) {
      return Set.of();
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      return walk.filter(Files::isRegularFile).collect(Collectors.toUnmodifiableSet());
    }
  }
}
