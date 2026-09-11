package dev.vlaisanem.automation.tests.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import dev.vlaisanem.automation.core.AutomationTest;
import dev.vlaisanem.automation.core.Steps;
import dev.vlaisanem.automation.ui.pages.HomePage;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Deliberately, deterministically fails its third step to exercise the runner's step/failure/
 * artifact drill-down. The first two steps do real read-only work; the third always fails,
 * independent of the app under test. Run via the {@code fixtureTest} Gradle task.
 */
@AutomationTest
@Tag("ui")
@Tag("room")
@Tag("read-only")
@Tag("regression")
@Tag("fixture")
@Epic("Runner platform")
@Feature("Step drill-down fixture")
class StepDrilldownFixtureTest {

  @Test
  @DisplayName(
      "Deliberately fails its third step, for step/failure/artifact drill-down verification")
  void deliberatelyFailsItsThirdStep(Page page, Steps steps) {
    HomePage homePage = new HomePage(page);

    steps.run("open the homepage", homePage::open);
    steps.run("assert the homepage loaded", homePage::assertLoaded);
    steps.run(
        "intentionally fail this step",
        () ->
            assertThat(homePage.bookableRoomCount())
                .as("deliberate fixture failure - not a real defect")
                .isEqualTo(-1));
  }
}
