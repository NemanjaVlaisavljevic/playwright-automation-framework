package dev.vlaisanem.automation.dashboarde2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import dev.vlaisanem.automation.dashboarde2e.pages.RunDetailsPage;
import dev.vlaisanem.automation.dashboarde2e.pages.RunsListPage;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * C4.6.5 - measures real, user-perceived render and filter latency against the same "~100 tests,
 * hundreds of steps" scenario as {@code LargeRunE2eTest} (C4.6.4), using {@code
 * SyntheticRunFixture}. Per the user's own instruction ("Merenje renderovanja i filtera;
 * virtualizacija samo ako postoji dokazani problem" - measure render/filter performance, virtualize
 * only if there's a proven problem), this test's job is to produce that proof or its absence, not
 * to assume either way.
 *
 * <p>Each measurement is the monotonic elapsed time ({@link System#nanoTime()}, not {@link
 * java.time.Instant#now()}) from firing a real user action to the moment Playwright's own
 * auto-waiting assertion observes the resulting DOM state - i.e. real, observable latency (for the
 * initial render: the launch click, the POST/redirect, SSE connection setup, and reducer/React
 * work, not just the render alone - a real review finding caught this test starting its clock only
 * after {@code launchRun()} had already returned, understating what it claimed to measure), not a
 * synthetic {@code performance.now()} span around only part of that pipeline. Thresholds are
 * documented in-line with the reasoning behind each one; a violation prints the actual measured
 * duration so a maintainer deciding whether to revisit virtualization has real numbers, not a bare
 * pass/fail.
 */
@ExtendWith(DashboardE2eEnvironment.class)
class RenderPerformanceE2eTest {

  private static final int TEST_COUNT = 100;
  private static final int STEPS_PER_TEST = 4;

  // Generous headroom over what a real interactive session needs to feel instant (sub-100ms is the
  // usual UX guideline) - this environment is a shared CI-style machine running a headless browser
  // alongside a JVM backend, not a quiet desktop, so the bar is "clearly fine," not "barely
  // passes."
  private static final Duration INITIAL_RENDER_BUDGET = Duration.ofSeconds(3);
  private static final Duration FILTER_INTERACTION_BUDGET = Duration.ofSeconds(2);
  private static final Duration EXPAND_STEPS_BUDGET = Duration.ofSeconds(2);

  @Test
  @Timeout(60)
  void renderingAHundredTestsAndFilteringThemStaysWellWithinBudget(
      Page page, BrowserContext context) {
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

    RunsListPage runsList = RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL);

    // Started before launchRun() itself, not after - launchRun() clicks Run and waits out the
    // POST/redirect, during which the SSE connection and a meaningful chunk of reducer/render work
    // can already complete. Starting the clock after it returned understated this measurement's
    // own budget-relevant window and could hide a real multi-second launch/navigation regression -
    // a real review finding (P1), fixed here.
    long renderStartNanos = System.nanoTime();
    RunDetailsPage details = runsList.launchRun();
    details.waitForConnectionState("Run finished.", Duration.ofSeconds(30));
    // waitForConnectionState only proves the banner text updated - the last test row rendering is
    // the actual "100 rows are on screen" signal this measurement cares about.
    assertThat(details.testRow(SyntheticRunFixture.testDisplayName(TEST_COUNT))).isVisible();
    Duration renderDuration = elapsedSince(renderStartNanos);
    System.out.println(
        "PERF initial render of "
            + TEST_COUNT
            + " tests: "
            + renderDuration.toMillis()
            + "ms (budget "
            + INITIAL_RENDER_BUDGET.toMillis()
            + "ms)");
    assertThat(renderDuration)
        .as(
            "initial render of %d tests took %dms - if this ever regresses past the budget,"
                + " that is the proof needed to justify virtualizing the Tests table",
            TEST_COUNT, renderDuration.toMillis())
        .isLessThanOrEqualTo(INITIAL_RENDER_BUDGET);

    long filterStartNanos = System.nanoTime();
    details.testSearchInput().fill("test 099");
    assertThat(details.testRow(SyntheticRunFixture.testDisplayName(99))).isVisible();
    assertThat(details.testRow(SyntheticRunFixture.testDisplayName(1))).isHidden();
    Duration filterDuration = elapsedSince(filterStartNanos);
    System.out.println(
        "PERF search-filter narrowing "
            + TEST_COUNT
            + " tests to 1: "
            + filterDuration.toMillis()
            + "ms (budget "
            + FILTER_INTERACTION_BUDGET.toMillis()
            + "ms)");
    assertThat(filterDuration)
        .as("search-filter interaction took %dms", filterDuration.toMillis())
        .isLessThanOrEqualTo(FILTER_INTERACTION_BUDGET);
    details.clearFiltersButton().click();

    long expandStartNanos = System.nanoTime();
    String midTestName = SyntheticRunFixture.testDisplayName(50);
    details.expandSteps(midTestName);
    assertThat(details.stepRow(SyntheticRunFixture.stepName(50, STEPS_PER_TEST))).isVisible();
    Duration expandDuration = elapsedSince(expandStartNanos);
    System.out.println(
        "PERF expanding one test's "
            + STEPS_PER_TEST
            + " steps: "
            + expandDuration.toMillis()
            + "ms (budget "
            + EXPAND_STEPS_BUDGET.toMillis()
            + "ms)");
    assertThat(expandDuration)
        .as("expanding a test's steps took %dms", expandDuration.toMillis())
        .isLessThanOrEqualTo(EXPAND_STEPS_BUDGET);
  }

  private static String extractRunId(String eventsUrl) {
    return eventsUrl.replaceAll(".*/runs/([^/]+)/events.*", "$1");
  }

  /**
   * {@link System#nanoTime()}, not {@link java.time.Instant#now()} - a monotonic clock immune to
   * wall-clock adjustments (NTP corrections, DST), which is what an elapsed-time measurement like
   * this actually needs.
   */
  private static Duration elapsedSince(long startNanos) {
    return Duration.ofNanos(System.nanoTime() - startNanos);
  }
}
