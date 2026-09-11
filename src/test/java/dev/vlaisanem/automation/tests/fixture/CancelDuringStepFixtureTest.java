package dev.vlaisanem.automation.tests.fixture;

import com.microsoft.playwright.Page;
import dev.vlaisanem.automation.core.AutomationTest;
import dev.vlaisanem.automation.core.Steps;
import dev.vlaisanem.automation.ui.pages.HomePage;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Deliberately blocks mid-step so {@code CancelE2eTest} can cancel it deterministically, verifying
 * cancellation/{@code INTERRUPTED} reconciliation. Run via the {@code fixtureTest} Gradle task.
 *
 * <p>The {@code Thread.sleep} below is an intentional exception to this project's no-sleep rule: it
 * simulates a long-running step for another test's timing needs, not a functional wait.
 */
@AutomationTest
@Tag("ui")
@Tag("room")
@Tag("read-only")
@Tag("regression")
@Tag("fixture")
@Epic("Runner platform")
@Feature("Cancellation reconciliation fixture")
class CancelDuringStepFixtureTest {

  /**
   * Long enough for a real E2E test to observe {@code STEP_STARTED} before cancelling; short enough
   * not to slow down {@code StepDrilldownE2eTest}, which also waits through this block.
   */
  private static final Duration BLOCK_DURATION = Duration.ofSeconds(8);

  @Test
  @DisplayName(
      "Deliberately blocks mid-step, for cancellation/INTERRUPTED reconciliation verification")
  void deliberatelyBlocksDuringItsSecondStep(Page page, Steps steps) {
    HomePage homePage = new HomePage(page);

    steps.run("open the homepage", homePage::open);
    steps.run("block until cancelled", CancelDuringStepFixtureTest::sleepUninterruptibly);
  }

  private static void sleepUninterruptibly() {
    try {
      Thread.sleep(BLOCK_DURATION.toMillis());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
