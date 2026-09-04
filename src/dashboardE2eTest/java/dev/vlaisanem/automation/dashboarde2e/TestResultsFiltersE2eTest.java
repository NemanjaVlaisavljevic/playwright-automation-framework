package dev.vlaisanem.automation.dashboarde2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.AriaRole;
import dev.vlaisanem.automation.dashboarde2e.pages.RunDetailsPage;
import dev.vlaisanem.automation.dashboarde2e.pages.RunsListPage;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Real-browser proof for the C4.4 search/status/evidence filters and their Live Focus reveal
 * interaction (its own DoD explicitly calls for this, not just component-level coverage) - against
 * a real {@code runner-service} + real {@code runner-dashboard} + real Chromium.
 *
 * <p>Launches the {@code FIXTURE} suite specifically, not {@code SMOKE}: the two fixtures'
 * `@DisplayName`s and step names are stable, deterministic contract this project already promises
 * to keep (see {@code StepDrilldownFixtureTest}/{@code CancelDuringStepFixtureTest}'s own Javadoc);
 * {@code RunLifecycleE2eTest}'s own comment explicitly warns that SMOKE's suite membership can
 * change independently of any one test that assumes a specific name in it, which the filters here
 * absolutely would if they relied on it.
 */
@ExtendWith(DashboardE2eEnvironment.class)
class TestResultsFiltersE2eTest {

  private static final String DRILLDOWN_TEST_NAME =
      "Deliberately fails its third step, for step/failure/artifact drill-down verification";
  private static final String CANCEL_FIXTURE_TEST_NAME =
      "Deliberately blocks mid-step, for cancellation/INTERRUPTED reconciliation verification";

  @Test
  @Timeout(90)
  void searchAndStatusAndEvidenceFiltersNarrowTheVisibleTests(Page page) {
    RunsListPage runsList =
        RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL).selectSuite("FIXTURE");
    RunDetailsPage details = runsList.launchRun();

    // Same terminal status StepDrilldownE2eTest already relies on for this suite - one fixture
    // always fails on purpose, so the run itself always ends FAILED.
    details.waitForStatus("FAILED", Duration.ofSeconds(60));

    // Search by test name: only the matching test's own row remains.
    details.testSearchInput().fill("cancellation");
    assertThat(details.testRow(CANCEL_FIXTURE_TEST_NAME)).isVisible();
    assertThat(details.testRow(DRILLDOWN_TEST_NAME)).not().isVisible();

    // Search by a step name that belongs only to the other test: reveals it, force-expanded, with
    // every one of its steps visible for context - not just the one that matched.
    details.testSearchInput().fill("intentionally fail");
    assertThat(details.testRow(DRILLDOWN_TEST_NAME)).isVisible();
    assertThat(exactText(details.stepRow("open the homepage"), "PASSED")).isVisible();
    assertThat(exactText(details.stepRow("assert the homepage loaded"), "PASSED")).isVisible();
    assertThat(exactText(details.stepRow("intentionally fail this step"), "FAILED")).isVisible();

    details.testSearchInput().fill("");

    // Status filter: "Problems" shows only the FAILED fixture.
    details.testStatusFilter().selectOption("PROBLEMS");
    assertThat(details.testRow(DRILLDOWN_TEST_NAME)).isVisible();
    assertThat(details.testRow(CANCEL_FIXTURE_TEST_NAME)).not().isVisible();

    // Evidence filter: only the FAILED fixture captured a screenshot/trace.
    details.testStatusFilter().selectOption("ALL");
    details.testEvidenceFilter().selectOption("HAS_ARTIFACTS");
    assertThat(details.testRow(DRILLDOWN_TEST_NAME)).isVisible();
    assertThat(details.testRow(CANCEL_FIXTURE_TEST_NAME)).not().isVisible();

    // Clear filters brings everything back.
    details.clearFiltersButton().click();
    assertThat(details.testSearchInput()).hasValue("");
    assertThat(details.testStatusFilter()).hasValue("ALL");
    assertThat(details.testEvidenceFilter()).hasValue("ALL");
    assertThat(details.testRow(DRILLDOWN_TEST_NAME)).isVisible();
    assertThat(details.testRow(CANCEL_FIXTURE_TEST_NAME)).isVisible();
  }

  @Test
  @Timeout(90)
  void liveFocusRevealsAndFocusesATestCurrentlyHiddenByAFilter(Page page) {
    RunsListPage runsList =
        RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL).selectSuite("FIXTURE");
    RunDetailsPage details = runsList.launchRun();

    // The blocked-step fixture reliably stays RUNNING for ~8s (see its own Javadoc) - a real
    // window to filter it out of the table while Live Focus still tracks it as active.
    assertThat(exactText(details.stepRow("block until cancelled"), "RUNNING"))
        .isVisible(
            new LocatorAssertions.IsVisibleOptions().setTimeout(Duration.ofSeconds(30).toMillis()));

    // A search that matches neither fixture's own name hides both rows from the table...
    details.testSearchInput().fill("no such test");
    assertThat(details.testRow(CANCEL_FIXTURE_TEST_NAME)).not().isVisible();

    // ...but Live Focus is unaffected by the Tests table's own filter - it still shows the real
    // active test and its currently-blocked step.
    Locator activeTestItem =
        details
            .liveFocusPanel()
            .getByRole(
                AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(CANCEL_FIXTURE_TEST_NAME));
    assertThat(activeTestItem).isVisible();

    activeTestItem.click();

    // Clicking it resets the filter that was hiding it, reveals its row, and gives it real,
    // observable DOM focus - not just scrolls the page to it.
    assertThat(details.testSearchInput()).hasValue("");
    Locator row = details.testRow(CANCEL_FIXTURE_TEST_NAME);
    assertThat(row).isVisible();
    Boolean isFocused = (Boolean) row.evaluate("el => el === document.activeElement");
    assertThat(isFocused).isTrue();

    // Let the fixture finish on its own (it just returns once the block ends) rather than leaving
    // an orphaned RUNNING run for another test to trip over - `DashboardE2eEnvironment` also
    // guards this, but finishing cleanly here keeps this test's own intent self-contained.
    details.waitForStatus("FAILED", Duration.ofSeconds(30));
  }

  private static Locator exactText(Locator scope, String text) {
    return scope.getByText(text, new Locator.GetByTextOptions().setExact(true));
  }
}
