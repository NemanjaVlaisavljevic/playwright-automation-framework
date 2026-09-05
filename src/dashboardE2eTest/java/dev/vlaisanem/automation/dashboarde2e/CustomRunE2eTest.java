package dev.vlaisanem.automation.dashboarde2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import dev.vlaisanem.automation.dashboarde2e.pages.RunDetailsPage;
import dev.vlaisanem.automation.dashboarde2e.pages.RunsListPage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * D0.5's own permanent, CI-enforced proof for {@code Suite.CUSTOM} - a manual Claude-in-Chrome pass
 * verified this flow once during development, but that is not a substitute for a committed
 * regression test (see the review that asked for this). Two stable, fast, {@code PUBLIC} tests -
 * one {@code API}, one {@code UI} - are picked through the real {@code CustomTestPicker}, so this
 * exercises the full server-catalog-only chain end to end: {@code GET /api/v1/tests} populates the
 * picker, the checked {@code testKey}s are submitted, {@code CustomTestSelectionValidator} accepts
 * them, and {@code customTest --tests ...} actually runs just those two.
 *
 * <p>A third catalog test that was never selected is asserted absent from the results - proving
 * this was a real filtered {@code --tests} invocation, not a full {@code customTest} run that
 * happened to include the two selected tests among everything else.
 */
@ExtendWith(DashboardE2eEnvironment.class)
class CustomRunE2eTest {

  private static final String FIRST_TEST_DISPLAY_NAME =
      "GET /api/room returns a usable room inventory";
  private static final String SECOND_TEST_DISPLAY_NAME = "Guest can see at least one bookable room";
  private static final String NOT_SELECTED_TEST_DISPLAY_NAME =
      "Admin can obtain a non-empty session token";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  @Timeout(150)
  void launchesExactlyTheTwoSelectedPublicTestsAndNothingElse(Page page) {
    RunsListPage runsList = RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL);
    // PUBLIC is the form's own default environment (see RunLaunchForm.tsx); CUSTOM only exists for
    // it (RunCatalog only maps PUBLIC+CUSTOM to a Gradle task).
    runsList
        .selectSuite("CUSTOM")
        .selectCustomTest(FIRST_TEST_DISPLAY_NAME)
        .selectCustomTest(SECOND_TEST_DISPLAY_NAME);

    assertThat(runsList.customSelectionCount()).hasText("2 tests selected");

    RunDetailsPage details = runsList.launchRun();

    details.waitForStatus("SUCCEEDED", Duration.ofSeconds(90));

    assertThat(details.metricValue("Total")).hasText("2");
    assertThat(details.metricValue("Passed")).hasText("2");
    assertThat(details.testRow(FIRST_TEST_DISPLAY_NAME)).isVisible();
    assertThat(details.testRow(SECOND_TEST_DISPLAY_NAME)).isVisible();
    assertThat(details.testRow(NOT_SELECTED_TEST_DISPLAY_NAME)).hasCount(0);

    List<JsonNode> selectedTests = fetchSelectedTests(page, details.runId());
    assertThat(selectedTests).hasSize(2);
    assertThat(selectedTests)
        .extracting(node -> node.path("displayName").asText())
        .containsExactlyInAnyOrder(FIRST_TEST_DISPLAY_NAME, SECOND_TEST_DISPLAY_NAME);
  }

  /**
   * Confirms the REST response's own {@code selectedTests} snapshot - not just the rendered table -
   * matches the request exactly, via a direct same-origin {@code GET} (see {@code
   * DownloadLogE2eTest} for the same {@code page.request()} pattern).
   */
  private static List<JsonNode> fetchSelectedTests(Page page, String runId) {
    APIResponse response =
        page.request().get(DashboardE2eEnvironment.DASHBOARD_BASE_URL + "/api/v1/runs/" + runId);
    assertThat(response.status()).isEqualTo(200);
    JsonNode body;
    try {
      body = OBJECT_MAPPER.readTree(response.text());
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
    List<JsonNode> selectedTests = new ArrayList<>();
    body.path("selectedTests").forEach(selectedTests::add);
    return selectedTests;
  }
}
