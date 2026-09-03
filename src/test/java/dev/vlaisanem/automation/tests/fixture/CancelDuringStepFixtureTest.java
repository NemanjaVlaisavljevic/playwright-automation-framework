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
 * A controlled fixture proving the dashboard's cancellation reconciliation (a test/step relabeled
 * {@code INTERRUPTED} once the run ends while it was still {@code RUNNING} - see {@code
 * run-details-view-model.ts}): its second step deliberately blocks for a fixed, generous duration
 * so a real E2E test ({@code CancelE2eTest}) can reliably observe {@code STEP_STARTED} already
 * reported with no terminal event yet, before cancelling the run - rather than racing an ordinary
 * suite's own unpredictable timing (the exact review finding this fixture exists to fix).
 *
 * <p>The blocking {@code Thread.sleep} below is a deliberate, narrowly-scoped exception to this
 * project's "never {@code Thread.sleep}" rule: this is test <em>infrastructure</em> simulating a
 * long-running step for another test's own timing needs, not a functional assertion standing in for
 * a proper Playwright wait. Never included in any real suite - see the {@code fixture} tag's own
 * handling in {@code build.gradle}, identical to {@link StepDrilldownFixtureTest}. Run on demand
 * via the {@code fixtureTest} Gradle task or the runner's {@code FIXTURE} suite, alongside that
 * other fixture - a viewer of a {@code FIXTURE} run will see both.
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
   * Long enough that a real E2E test (a network round trip plus a couple of Playwright actions)
   * reliably observes this step's own {@code STEP_STARTED} before cancelling; short enough not to
   * meaningfully slow down the existing {@code StepDrilldownE2eTest}, which also launches {@code
   * FIXTURE} and therefore waits out this same block on every run.
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
