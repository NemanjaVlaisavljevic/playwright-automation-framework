package dev.vlaisanem.automation.dashboarde2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import dev.vlaisanem.automation.dashboarde2e.pages.RunsListPage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * D4.4.3d - the real committed {@code runner-dashboard/public/performance/baseline.json} (see
 * {@code runner-dashboard/scripts/summarize-baseline.mjs}), rendered by the real built dashboard
 * bundle in a real Chromium. The "known" provenance/metric values asserted below are read straight
 * from that same on-disk artifact via {@link #readCommittedBaseline()} rather than duplicated here
 * as a second hardcoded copy - a future baseline republish (a new archived CI run) must never leave
 * this test quietly asserting stale numbers.
 */
@ExtendWith(DashboardE2eEnvironment.class)
class PerformancePageE2eTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @Timeout(30)
  void directDeepLinkRendersKnownProvenanceAndMetricsWithNoAxeViolations(Page page) {
    JsonNode baseline = readCommittedBaseline();
    JsonNode source = baseline.path("source");
    String shortSha = source.path("commitSha").asText().substring(0, 7);
    long workflowRunId = source.path("workflowRun").path("id").asLong();
    String workflowRunUrl = source.path("workflowRun").path("url").asText();

    // "public-read"/"capabilities" are stable ids from scenario-configs.mjs, not a positional
    // scenarios[0]/latencies[0] lookup - the contract permits a scenario with zero latency metrics
    // (sse-connection-cap is exactly that today), so indexing by position would silently break the
    // moment scenario order or shape changes. The *values* asserted below still come straight from
    // this same real, committed JSON, never duplicated as a second hardcoded copy.
    JsonNode scenario = findByField(baseline.path("scenarios"), "scenarioId", "public-read");
    String scenarioName = scenario.path("displayName").asText();
    JsonNode metric = findByField(scenario.path("latencies"), "metricId", "capabilities");
    String metricName = metric.path("displayName").asText();
    String expectedP95 = String.format(Locale.ROOT, "%.1f ms", metric.path("p95Ms").asDouble());

    // A direct deep link, not a click-through from /runs - the same "someone bookmarked/shared
    // this URL" scenario DeepLinkE2eTest proves for a run/step link.
    page.navigate(DashboardE2eEnvironment.DASHBOARD_BASE_URL + "/performance");
    page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Performance")).waitFor();

    assertThat(page.getByText("not live monitoring, no SLA")).isVisible();

    assertThat(dtValue(page, "Commit")).hasText(shortSha);
    Locator actionsRunLink = dtValue(page, "Actions run").getByRole(AriaRole.LINK);
    assertThat(actionsRunLink).containsText("Run #" + workflowRunId);
    assertThat(actionsRunLink).hasAttribute("href", workflowRunUrl);

    assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName(scenarioName)))
        .isVisible();
    Locator table = scenarioLatencyTable(page, scenarioName);
    Locator metricRow = metricRow(table, metricName);
    assertThat(metricRow).isVisible();
    assertThat(metricRow.getByText(expectedP95, new Locator.GetByTextOptions().setExact(true)))
        .isVisible();

    List<Map<String, Object>> violations = AccessibilityAudit.run(page);
    assertThat(violations).as(AccessibilityAudit.summarize(violations)).isEmpty();
  }

  @Test
  @Timeout(30)
  void sidebarNavigationToPerformanceSetsAriaCurrentOnlyOnThatLink(Page page) {
    RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL);

    Locator runsLink = sidebarLink(page, "Runs");
    Locator performanceLink = sidebarLink(page, "Performance");
    assertThat(runsLink).hasAttribute("aria-current", "page");
    assertThat(performanceLink).not().hasAttribute("aria-current", "page");

    performanceLink.click();
    page.waitForURL("**/performance");
    page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Performance")).waitFor();

    assertThat(performanceLink).hasAttribute("aria-current", "page");
    assertThat(runsLink).not().hasAttribute("aria-current", "page");
  }

  /** Scoped via the {@code <nav aria-label="Primary">} sidebar itself - see AppShell.tsx. */
  private static Locator sidebarLink(Page page, String name) {
    return page.locator("nav[aria-label='Primary']")
        .getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName(name));
  }

  /**
   * The Source section's {@code <dl>} pairs a {@code <dt>} label with its immediately-following
   * {@code <dd>} value (see {@code PerformancePage.tsx}) - mirrors {@code
   * RunDetailsPage#statusValue()}'s own {@code dt + dd} convention.
   */
  private static Locator dtValue(Page page, String label) {
    return page.locator("dt", new Page.LocatorOptions().setHasText(label)).locator("+ dd");
  }

  /**
   * Scoped via the {@code <caption>} {@code ScenarioSection.tsx} renders on its own latency table
   * ("Latency metrics for {scenario.displayName}", visually hidden but still in the DOM/a11y tree,
   * not {@code display:none}) - two scenarios can legitimately share a metric name (a review
   * finding: the previous version scoped only to {@code <td>}, which a repeated metric name across
   * scenarios would have resolved as a strict-mode-violating multi-element locator).
   */
  private static Locator scenarioLatencyTable(Page page, String scenarioDisplayName) {
    String captionText = "Latency metrics for " + scenarioDisplayName;
    return page.locator("caption", new Page.LocatorOptions().setHasText(captionText))
        .locator("xpath=ancestor::table[1]");
  }

  /** The metric's own row, scoped to the already-scenario-scoped table passed in. */
  private static Locator metricRow(Locator scenarioLatencyTable, String metricDisplayName) {
    return scenarioLatencyTable
        .locator("td", new Locator.LocatorOptions().setHasText(metricDisplayName))
        .locator("xpath=ancestor::tr[1]");
  }

  /** The first array element whose {@code field} equals {@code value} - a stable-id lookup. */
  private static JsonNode findByField(JsonNode array, String field, String value) {
    for (JsonNode node : array) {
      if (node.path(field).asText().equals(value)) {
        return node;
      }
    }
    throw new IllegalStateException(
        "no element with " + field + "=\"" + value + "\" found in " + array);
  }

  private static JsonNode readCommittedBaseline() {
    Path dashboardDir = Path.of(System.getProperty("dashboardE2e.dashboardDir"));
    Path baselinePath = dashboardDir.resolve("public/performance/baseline.json");
    try {
      return MAPPER.readTree(Files.readString(baselinePath));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
