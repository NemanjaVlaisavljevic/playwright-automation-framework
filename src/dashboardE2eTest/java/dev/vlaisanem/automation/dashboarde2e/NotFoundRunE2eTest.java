package dev.vlaisanem.automation.dashboarde2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Page;
import dev.vlaisanem.automation.dashboarde2e.pages.RunDetailsPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Navigating straight to a {@code runId} the (in-memory, restart-losing) backend has never heard
 * of, or no longer remembers.
 */
@ExtendWith(DashboardE2eEnvironment.class)
class NotFoundRunE2eTest {

  @Test
  @Timeout(15)
  void showsASpecificMessageForAnUnknownRunId(Page page) {
    RunDetailsPage.at(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL, "no-such-run-id");

    // DELIBERATE CI PROOF - REVERT BEFORE MERGING: wrong expected text, guaranteed to fail so the
    // dashboard-e2e.yml failure-artifact upload (screenshot/trace/video) can be verified for real.
    // Revert this one line back to "This run is no longer available" once confirmed.
    assertThat(page.getByText("DELIBERATE-CI-FAILURE-ARTIFACT-PROOF-2026-09-02")).isVisible();
  }
}
