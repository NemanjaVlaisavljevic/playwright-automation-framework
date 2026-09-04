package dev.vlaisanem.automation.dashboarde2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import dev.vlaisanem.automation.dashboarde2e.pages.RunDetailsPage;
import dev.vlaisanem.automation.dashboarde2e.pages.RunsListPage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * C4.6.4 - a synthesized (see {@code SyntheticRunFixture}) large run: 100 tests, 4 steps each (400
 * steps total, "hundreds of steps" per the C4.6 spec), 10 of the 100 tests failing on their last
 * step. Verifies the real built dashboard bundle renders this scale correctly - accurate
 * Total/Passed/Failed counts, the search/status filters still narrow correctly, a specific
 * deep-in-the-list test/step is reachable and correct, no document-level horizontal overflow (see
 * {@code ResponsiveE2eTest}), and no browser console error or uncaught page exception anywhere
 * during the whole load - a scale this large is exactly where an accidental O(n^2) render loop or a
 * silently-swallowed exception would first surface.
 */
@ExtendWith(DashboardE2eEnvironment.class)
class LargeRunE2eTest {

  private static final int TEST_COUNT = 100;
  private static final int STEPS_PER_TEST = 4;

  @Test
  @Timeout(60)
  void aHundredTestsWithHundredsOfStepsRendersCorrectlyWithNoErrors(
      Page page, BrowserContext context) {
    List<String> consoleErrors = new CopyOnWriteArrayList<>();
    List<String> pageErrors = new CopyOnWriteArrayList<>();
    page.onConsoleMessage(
        msg -> {
          if (msg.type().equals("error")) {
            consoleErrors.add(msg.text());
          }
        });
    page.onPageError(pageErrors::add);

    context.route(
        "**/api/v1/runs/*/events",
        route -> {
          String runId = extractRunId(route.request().url());
          route.fulfill(
              new Route.FulfillOptions()
                  .setStatus(200)
                  .setHeaders(Map.of("Content-Type", "text/event-stream"))
                  .setBody(SyntheticRunFixture.streamBody(runId, TEST_COUNT, STEPS_PER_TEST)));
        });

    RunDetailsPage details =
        RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL).launchRun();
    details.waitForConnectionState("Run finished.", Duration.ofSeconds(30));

    assertThat(details.metricNumber("Total")).isEqualTo(TEST_COUNT);
    assertThat(details.metricNumber("Passed")).isEqualTo(90);
    assertThat(details.metricNumber("Failed")).isEqualTo(10);

    // A test/step deep in the middle of the list, not just the first or last one - proves the
    // whole list actually rendered, not just a truncated prefix.
    String midTestName = SyntheticRunFixture.testDisplayName(50);
    assertThat(details.testRow(midTestName)).isVisible();
    details.expandSteps(midTestName);
    String midStepName = SyntheticRunFixture.stepName(50, 3);
    assertThat(details.stepRow(midStepName)).isVisible();

    // The C4.4 filters must still narrow correctly at this scale.
    details.testSearchInput().fill("test 010");
    assertThat(details.testRow(SyntheticRunFixture.testDisplayName(10))).isVisible();
    assertThat(details.testRow(SyntheticRunFixture.testDisplayName(11))).isHidden();
    details.testSearchInput().fill("");
    details.testStatusFilter().selectOption("FAILED");
    for (int t = 10; t <= 100; t += 10) {
      assertThat(details.testRow(SyntheticRunFixture.testDisplayName(t))).isVisible();
    }
    assertThat(details.testRow(SyntheticRunFixture.testDisplayName(1))).isHidden();
    details.clearFiltersButton().click();

    double scrollWidth =
        ((Number) page.evaluate("document.documentElement.scrollWidth")).doubleValue();
    double clientWidth =
        ((Number) page.evaluate("document.documentElement.clientWidth")).doubleValue();
    assertThat(scrollWidth)
        .as("document must not gain horizontal scroll room at this scale")
        .isLessThanOrEqualTo(clientWidth);

    assertThat(consoleErrors).as("no browser console errors during a 100-test render").isEmpty();
    assertThat(pageErrors).as("no uncaught page exceptions during a 100-test render").isEmpty();
  }

  private static String extractRunId(String eventsUrl) {
    return eventsUrl.replaceAll(".*/runs/([^/]+)/events.*", "$1");
  }
}
