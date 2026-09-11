package dev.vlaisanem.automation.tests.fixture;

import dev.vlaisanem.automation.core.AutomationTest;
import dev.vlaisanem.automation.core.Steps;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Not a real Restful Booker Platform test - emits many step events so a real regression test can
 * verify {@code RUNNER_RAW_EVENT_MAX_BYTES} is enforced. Uses only {@link Steps} (never {@code
 * Page}), so it stays browser-free and passes normally; run via {@code fixtureTest --tests <FQCN>}.
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
