package dev.vlaisanem.automation.dashboarde2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import dev.vlaisanem.automation.dashboarde2e.pages.RunDetailsPage;
import dev.vlaisanem.automation.dashboarde2e.pages.RunsListPage;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Protects the Faza B step/failure/artifact drill-down against a real {@code runner-service} + real
 * {@code runner-dashboard} + real Chromium browser, the same class of proof {@link
 * RunLifecycleE2eTest} gives the plain test-level flow - a Vitest/MSW component test can fake the
 * SSE payload and the REST responses, but only a real backend actually runs the {@code
 * StepDrilldownFixtureTest} fixture, writes its real manifest entries, and serves them back over a
 * real proxy.
 *
 * <p>Launches the {@code FIXTURE} suite specifically (not {@code SMOKE}): a deliberately,
 * deterministically failing single test built exactly for this purpose - see that class's own
 * Javadoc - so this never depends on the shared public Restful Booker Platform app happening to be
 * broken in some exploitable way.
 */
@ExtendWith(DashboardE2eEnvironment.class)
class StepDrilldownE2eTest {

  @Test
  @Timeout(90)
  void showsStepFailureDetailAndArtifactsScopedToTheFailingStep(Page page) {
    RunsListPage runsList = RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL);
    RunDetailsPage details = runsList.selectSuite("FIXTURE").launchRun();

    details.waitForConnectionState("Live", Duration.ofSeconds(15));
    details.waitForStatus("FAILED", Duration.ofSeconds(60));

    // Test rows with steps are collapsed by default - expand before any step content is even in
    // the DOM.
    details.expandSteps(
        "Deliberately fails its third step, for step/failure/artifact drill-down verification");

    // All three steps, by name and status - proves STEP_STARTED/STEP_PASSED/STEP_FAILED actually
    // reached the dashboard, not just the test's own terminal TEST_FAILED. Exact text match, not
    // Locator's own default substring/case-insensitive one: the failing step's own (collapsed but
    // still DOM-present) failure detail contains "AssertionFailedError", which a plain
    // getByText("FAILED") would also match as a substring, resolving to two elements.
    assertThat(exactText(details.stepRow("open the homepage"), "PASSED")).isVisible();
    assertThat(exactText(details.stepRow("assert the homepage loaded"), "PASSED")).isVisible();
    assertThat(exactText(details.stepRow("intentionally fail this step"), "FAILED")).isVisible();

    // The exception class, the (redacted-but-present) message, and at least one of this project's
    // own application stack frames - see FailureDetailFormatter, shared by Steps and the listener.
    String failureDetail = details.stepDetailText("intentionally fail this step");
    // AssertJ's own assertion failure type - see StepDrilldownFixtureTest's isEqualTo(-1) check.
    assertThat(failureDetail).contains("AssertionFailedError");
    assertThat(failureDetail).contains("deliberate fixture failure - not a real defect");
    assertThat(failureDetail).contains("\tat dev.vlaisanem.automation");

    // The two passing steps carry no artifacts at all - only the failing one does.
    assertThat(details.stepRow("open the homepage").getByRole(AriaRole.LINK)).hasCount(0);
    assertThat(details.stepRow("assert the homepage loaded").getByRole(AriaRole.LINK)).hasCount(0);

    Locator screenshotLink = details.stepArtifactLink("intentionally fail this step", "Screenshot");
    Locator traceLink = details.stepArtifactLink("intentionally fail this step", "Trace");
    assertThat(screenshotLink).isVisible();
    assertThat(traceLink).isVisible();

    // The links must not just exist in the DOM - they must actually resolve to the real captured
    // files through the real backend + Vite proxy, with the content type the download endpoint
    // itself derives from the artifact's own type (see ArtifactController).
    assertArtifactDownloads(page, screenshotLink.getAttribute("href"), "image/png");
    assertArtifactDownloads(page, traceLink.getAttribute("href"), "application/zip");
  }

  private void assertArtifactDownloads(Page page, String relativeUrl, String expectedContentType) {
    APIResponse response =
        page.request().get(DashboardE2eEnvironment.DASHBOARD_BASE_URL + relativeUrl);

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.headers().get("content-type")).containsIgnoringCase(expectedContentType);
    assertThat(response.body().length).isPositive();
  }

  private static Locator exactText(Locator scope, String text) {
    return scope.getByText(text, new Locator.GetByTextOptions().setExact(true));
  }
}
