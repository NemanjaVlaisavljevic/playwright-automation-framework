package dev.vlaisanem.automation.dashboarde2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Page;
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
  }
}
