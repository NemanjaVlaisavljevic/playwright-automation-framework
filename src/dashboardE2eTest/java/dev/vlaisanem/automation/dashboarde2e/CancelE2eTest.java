package dev.vlaisanem.automation.dashboarde2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

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
 * Launches the read-only REGRESSION suite (not SMOKE) specifically because it runs long enough to
 * reliably observe the run still {@code RUNNING} before clicking Cancel - a SMOKE run's ~20-30s
 * total lifetime leaves too little margin. This is deliberately a strict assertion, not "CANCELLED
 * or SUCCEEDED": a test tolerant of either outcome could stay green even if Cancel never actually
 * worked, simply because the run happened to finish on its own first every time. If REGRESSION ever
 * finishes before this test manages to click Cancel, it must fail - that is not a flaky test, that
 * is this test correctly reporting it never proved cancellation.
 */
@ExtendWith(DashboardE2eEnvironment.class)
class CancelE2eTest {

  @Test
  @Timeout(180)
  void cancellingARunningRunReachesCancelledAndNotSomethingElse(Page page) {
    RunsListPage runsList =
        RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL)
            .selectSuite("REGRESSION");
    RunDetailsPage details = runsList.launchRun();

    details.waitForStatus("RUNNING", Duration.ofSeconds(30));
    assertThat(details.cancelButton()).isEnabled();
    details.cancelButton().click();

    details.waitForStatus("CANCELLED", Duration.ofSeconds(120));
    assertThat(details.cancelButton()).not().isVisible();
    assertThat(details.downloadLogLink()).isVisible();

    // Regression check for the C4.1 finding: whichever test (and step, if it uses the Steps API)
    // happened to still be RUNNING the instant this cancellation landed must now show INTERRUPTED,
    // not stay stuck on RUNNING forever just because it never got its own terminal event. This does
    // not depend on knowing which specific test/step was active - once the run itself is terminal,
    // nothing on the page may still read exactly "RUNNING".
    assertThat(details.anyRunningStatusBadge()).hasCount(0);
  }

  /**
   * Regression test for a real review finding: the test above only waits for the <em>run</em>
   * itself to reach RUNNING - Cancel could land before any test even starts, or between two tests,
   * in which case "no RUNNING text remains" afterward passes trivially without proving anything
   * about reconciling an actually-active test or step. Launches the deterministic {@code
   * CancelDuringStepFixtureTest} fixture instead (via the {@code FIXTURE} suite) specifically so
   * this test can wait for its own test row - and its own currently-blocked step - to genuinely be
   * RUNNING before clicking Cancel, then prove all three DoD requirements: the parent test itself
   * shows INTERRUPTED, the step that was actually still running shows INTERRUPTED, and the step
   * that had already finished keeps its real terminal status.
   *
   * <p>Also doubles as the C4.2 live-focus-panel E2E proof the plan calls for: this same
   * deterministic blocked-step fixture is exactly what lets a real browser test observe the panel
   * showing one specific, known test+step (rather than racing real journey-test timing), and then
   * confirm it disappears once the run reaches its terminal {@code CANCELLED} status.
   */
  @Test
  @Timeout(90)
  void cancellingDuringAnActiveStepMarksTheTestAndActiveStepInterrupted(Page page) {
    String testDisplayName =
        "Deliberately blocks mid-step, for cancellation/INTERRUPTED reconciliation verification";

    RunsListPage runsList =
        RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL).selectSuite("FIXTURE");
    RunDetailsPage details = runsList.launchRun();

    // No explicit expand needed - a RUNNING test's row auto-expands on its own (see
    // TestResultRow.tsx), and this test is still RUNNING (blocked) at this point. Clicking the
    // toggle here would incorrectly *collapse* an already-auto-expanded row.
    assertThat(exactText(details.stepRow("block until cancelled"), "RUNNING"))
        .isVisible(
            new LocatorAssertions.IsVisibleOptions().setTimeout(Duration.ofSeconds(30).toMillis()));

    // The live-focus panel independently shows the same test and its currently-blocked step, before
    // cancellation - scoped to the panel's own clickable item (by role, not `getByText`): the panel
    // also carries a visually-hidden aria-live paragraph announcing the very same text for screen
    // readers, which a plain text search would match too, a real strict-mode violation this
    // project's own E2E run caught.
    Locator activeTestItem =
        details
            .liveFocusPanel()
            .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(testDisplayName));
    assertThat(activeTestItem).isVisible();
    assertThat(
            activeTestItem.getByText(
                "block until cancelled", new Locator.GetByTextOptions().setExact(true)))
        .isVisible();

    details.cancelButton().click();
    details.waitForStatus("CANCELLED", Duration.ofSeconds(30));

    // The run is now terminal - the live-focus panel disappears entirely (not just visually hidden;
    // LiveFocusPanel.tsx stops rendering it), not merely "no longer showing this specific test".
    assertThat(details.liveFocusPanel()).hasCount(0);

    // INTERRUPTED is a terminal display status like any other - the row that was auto-expanded
    // while RUNNING correctly auto-collapses once it stops being RUNNING (see the "auto-expand must
    // use display status" requirement), so it must be explicitly reopened to see its steps again.
    details.expandSteps(testDisplayName);

    assertThat(exactText(details.testRow(testDisplayName), "INTERRUPTED")).isVisible();
    assertThat(exactText(details.stepRow("open the homepage"), "PASSED")).isVisible();
    assertThat(exactText(details.stepRow("block until cancelled"), "INTERRUPTED")).isVisible();
  }

  private static Locator exactText(Locator scope, String text) {
    return scope.getByText(text, new Locator.GetByTextOptions().setExact(true));
  }
}
