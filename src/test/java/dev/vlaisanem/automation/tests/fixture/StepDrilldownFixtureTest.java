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
 * A deliberately, deterministically failing fixture - not a real Restful Booker Platform feature
 * test. It exists to exercise the runner's step/failure/artifact drill-down end to end (Faza B)
 * without depending on the shared public app ever actually misbehaving: the first two steps do
 * real, harmless read-only work against the app; the third step asserts something that is always
 * false, independent of anything the app does.
 *
 * <p>Tagged {@code fixture} and excluded from every real suite at the Gradle level (see
 * build.gradle's {@code excludeTags 'fixture'}) - {@code regression} is still present because
 * {@code AutomationExtension} requires it unconditionally on every test regardless of suite
 * membership. Run on demand via the {@code fixtureTest} Gradle task or the runner's {@code FIXTURE}
 * suite.
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
