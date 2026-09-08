package dev.vlaisanem.automation.tests.fixture;

import dev.vlaisanem.automation.core.AutomationTest;
import dev.vlaisanem.automation.core.Steps;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Not a real Restful Booker Platform feature test - exists purely to back D4.2's real
 * property-forwarding regression test (see {@code RawEventAndAllurePropagationRealGradleTest} in
 * runner-service's {@code databaseIntegrationTest} source set), which spawns a real, non-daemon
 * child Gradle invocation of this exact test class with a deliberately tiny {@code
 * RUNNER_RAW_EVENT_MAX_BYTES} to prove the configured limit genuinely reaches {@code
 * RunnerEventWriterRegistry} inside the forked JUnit test-worker JVM.
 *
 * <p>Requests only {@link Steps} (never {@code Page}), so it never launches a browser - fast and
 * network-free. Each step emits its own {@code STEP_STARTED}/{@code STEP_PASSED} raw event line;
 * enough of them reliably exceed even a very small configured byte cap. Passes normally otherwise -
 * unlike its two fixture siblings, nothing here is meant to fail or block.
 *
 * <p>Tagged {@code fixture} and excluded from every real suite at the Gradle level (see
 * build.gradle's {@code excludeTags 'fixture'}) - {@code regression} is still present because
 * {@code AutomationExtension} requires it unconditionally on every test regardless of suite
 * membership. Reached on demand via {@code ./gradlew.bat fixtureTest --tests <this class's FQCN>},
 * never the whole {@code fixtureTest} task (its other two members deliberately fail/block).
 */
@AutomationTest
@Tag("ui")
@Tag("room")
@Tag("read-only")
@Tag("regression")
@Tag("fixture")
@Epic("Runner platform")
@Feature("Raw event overflow fixture")
class RawEventOverflowFixtureTest {

  private static final int STEP_COUNT = 200;

  @Test
  @DisplayName("Emits many step events to exercise the D4.2 raw-event overflow guard")
  void emitsManyStepEvents(Steps steps) {
    for (int i = 0; i < STEP_COUNT; i++) {
      steps.run("fixture step " + i, () -> {});
    }
  }
}
