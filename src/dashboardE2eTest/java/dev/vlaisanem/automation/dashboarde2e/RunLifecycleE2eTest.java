package dev.vlaisanem.automation.dashboarde2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import dev.vlaisanem.automation.dashboarde2e.pages.RunDetailsPage;
import dev.vlaisanem.automation.dashboarde2e.pages.RunsListPage;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The core happy path, against the real {@code runner-service} + real {@code runner-dashboard} + a
 * real Chromium browser: launching a run redirects to its details page, which then shows live SSE
 * progress through to a terminal REST-confirmed status. No fake {@code EventSource}, no MSW - see
 * {@code docs/SSE_CONTRACT_V1.md} and the dashboard's own README for what this is proving that the
 * Vitest/MSW component suite structurally cannot: the real browser, the real Vite proxy, and the
 * real {@code EventSource} transport all actually working together.
 *
 * <p>Asserting only {@code SUCCEEDED} would still pass even if the listener/ingestor pipeline lost
 * every {@code TEST_STARTED}/{@code TEST_PASSED} event along the way and only {@code RUN_FINISHED}
 * ever arrived - {@code Total}/{@code Passed} being positive closes that gap without pinning this
 * test to any one test's name (Restful Booker Platform's own SMOKE suite membership can change
 * independently of this suite), proving the full chain end to end: JUnit listener -> raw JSONL ->
 * ingestor -> journal -> SSE -> reducer -> UI table. Read as a number via {@code metricNumber}, not
 * asserted with {@code Locator}'s own text matcher - {@code hasText("0").not()} would itself
 * incorrectly fail once the SMOKE suite reaches a double-digit total, since {@code hasText} matches
 * by substring ("0" is contained in "10").
 */
@ExtendWith(DashboardE2eEnvironment.class)
class RunLifecycleE2eTest {

  @Test
  @Timeout(150)
  void launchesASmokeRunAndReachesATerminalStatus(Page page) {
    RunsListPage runsList = RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL);
    // PUBLIC/SMOKE are already the form's own defaults (see RunLaunchForm.tsx) - a real SMOKE run
    // against the real public target normally takes on the order of 20-30s end to end; the 90s
    // margin below absorbs real network variance against that public site, not this dashboard's
    // own overhead.
    RunDetailsPage details = runsList.launchRun();

    details.waitForConnectionState("Live", Duration.ofSeconds(15));
    details.waitForStatus("SUCCEEDED", Duration.ofSeconds(90));
    details.waitForConnectionState("Run finished.", Duration.ofSeconds(5));

    assertThat(details.cancelButton()).not().isVisible();
    assertThat(details.downloadLogLink()).isVisible();
    assertThat(details.metricNumber("Total")).isPositive();
    assertThat(details.metricNumber("Passed")).isPositive();
  }
}
