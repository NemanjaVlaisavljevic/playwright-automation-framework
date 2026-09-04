package dev.vlaisanem.automation.dashboarde2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.AriaRole;
import dev.vlaisanem.automation.dashboarde2e.pages.RunDetailsPage;
import dev.vlaisanem.automation.dashboarde2e.pages.RunsListPage;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Real-browser proof for C4.5: a copied deep link, opened in a brand-new browser tab (a genuinely
 * separate {@link Page} in the same {@link BrowserContext}, not just a fresh navigation on the same
 * one - the closest Playwright equivalent to "pasted into a new tab"), actually reveals the right
 * test and step against a real {@code runner-service} + real {@code runner-dashboard} + real
 * Chromium - not a mocked clipboard or a synthetic `RunResultTarget`.
 *
 * <p>Launches the {@code FIXTURE} suite - {@code StepDrilldownFixtureTest} deterministically fails
 * its own third step with a real screenshot/trace already captured, exactly the scenario the C4.5
 * spec's own "Real-browser E2E" section names as the best fit, for the same reason {@code
 * StepDrilldownE2eTest} already relies on it: stable, deterministic names, not a race against real
 * journey-test timing.
 */
@ExtendWith(DashboardE2eEnvironment.class)
class DeepLinkE2eTest {

  private static final String DRILLDOWN_TEST_NAME =
      "Deliberately fails its third step, for step/failure/artifact drill-down verification";
  private static final String FAILED_STEP_NAME = "intentionally fail this step";

  @Test
  @Timeout(90)
  void copiedStepLinkOpensInANewTabAndRevealsTheRealFailedStep(Page page, BrowserContext context) {
    context.grantPermissions(List.of("clipboard-read", "clipboard-write"));

    RunsListPage runsList =
        RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL).selectSuite("FIXTURE");
    RunDetailsPage details = runsList.launchRun();

    details.waitForStatus("FAILED", Duration.ofSeconds(60));
    details.expandSteps(DRILLDOWN_TEST_NAME);
    assertThat(exactText(details.stepRow(FAILED_STEP_NAME), "FAILED")).isVisible();

    details.copyStepLinkButton(FAILED_STEP_NAME).click();
    String copiedUrl = (String) page.evaluate("() => navigator.clipboard.readText()");
    assertThat(copiedUrl).contains(DashboardE2eEnvironment.DASHBOARD_BASE_URL);
    assertThat(copiedUrl).contains("stepId=");

    // A genuinely separate page/tab - the same "open a link someone sent you" scenario a real
    // viewer would be in, not merely `page.navigate()` reusing all of this page's own live state.
    Page newTab = context.newPage();
    newTab.navigate(copiedUrl);
    assertRevealed(newTab);

    // Reloading the very same URL against this now-terminal run repeats the same reveal - deep
    // links work for a completed run's REST-fed history, not only a live one.
    newTab.reload();
    assertRevealed(newTab);

    newTab.close();
  }

  /**
   * The target step must actually be visible (its parent test expanded) and hold real DOM focus,
   * with its screenshot/trace links present - proving the reveal, not just that the page loaded
   * without crashing.
   */
  private void assertRevealed(Page page) {
    Locator failedStepRow =
        page.getByRole(AriaRole.LISTITEM)
            .filter(new Locator.FilterOptions().setHasText(FAILED_STEP_NAME));
    assertThat(failedStepRow)
        .isVisible(
            new LocatorAssertions.IsVisibleOptions().setTimeout(Duration.ofSeconds(15).toMillis()));
    Boolean isFocused = (Boolean) failedStepRow.evaluate("el => el === document.activeElement");
    assertThat(isFocused).isTrue();
    assertThat(
            failedStepRow.getByRole(
                AriaRole.LINK, new Locator.GetByRoleOptions().setName("Screenshot for")))
        .isVisible();
    assertThat(
            failedStepRow.getByRole(
                AriaRole.LINK, new Locator.GetByRoleOptions().setName("Download trace")))
        .isVisible();
  }

  private static Locator exactText(Locator scope, String text) {
    return scope.getByText(text, new Locator.GetByTextOptions().setExact(true));
  }
}
