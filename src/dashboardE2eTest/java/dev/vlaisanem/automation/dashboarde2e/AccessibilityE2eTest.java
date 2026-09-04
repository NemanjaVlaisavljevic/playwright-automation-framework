package dev.vlaisanem.automation.dashboarde2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import dev.vlaisanem.automation.dashboarde2e.pages.RunDetailsPage;
import dev.vlaisanem.automation.dashboarde2e.pages.RunsListPage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * C4.6's automated accessibility gate - a real axe-core audit (see {@link AccessibilityAudit})
 * against the real built dashboard bundle, in a real Chromium, across the page states that matter
 * most: the plain runs list, a run mid-flight with the C4.2 Live Focus panel actually rendered, and
 * a run details page with its full failure workspace (steps, filters, copy links) expanded - an
 * empty/idle page alone would miss most of the real interactive surface this whole C4 phase built.
 */
@ExtendWith(DashboardE2eEnvironment.class)
class AccessibilityE2eTest {

  @Test
  @Timeout(30)
  void runsListPageHasNoAxeViolations(Page page) {
    RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL);

    List<Map<String, Object>> violations = AccessibilityAudit.run(page);
    assertThat(violations).as(AccessibilityAudit.summarize(violations)).isEmpty();
  }

  /**
   * Two audits against the same {@code FIXTURE} run, not one: the C4.2 Live Focus panel only exists
   * in the DOM while the run is still non-terminal (see {@code LiveFocusPanel}'s own early {@code
   * return null}), so a single audit taken after the run reaches {@code FAILED} - as this test used
   * to do - never actually exercised it, despite this class's own original Javadoc claiming Live
   * Focus was covered. {@code CancelDuringStepFixtureTest}'s second step deterministically blocks
   * for a fixed 8s (see its own Javadoc) specifically so a real E2E test can reliably observe it
   * mid-step rather than racing arbitrary timing - the exact mechanism this test now leans on to
   * catch the Live Focus panel while it is genuinely on screen.
   */
  @Test
  @Timeout(180)
  void runDetailsPageHasNoAxeViolationsWhileLiveAndAfterFailureWorkspaceExpands(Page page) {
    RunsListPage runsList =
        RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL).selectSuite("FIXTURE");
    RunDetailsPage details = runsList.launchRun();

    // "block until cancelled" is CancelDuringStepFixtureTest's own second step name, rendered
    // verbatim as the Live Focus panel's active-step label (see LiveFocusPanel.tsx's
    // activeStepLabel) - waiting for this text is a deterministic proxy for "the run is RUNNING
    // and the Live Focus panel is rendered with a real active test/step", not a race against
    // arbitrary suite timing. An explicit 75s budget, not Playwright's 30s default: a cold
    // Gradle/JUnit start or a prior test's queued backend cleanup can burn a meaningful chunk of
    // the fixed 8s active window before it even begins - a real review-round failure (a bare 30s
    // timeout on this exact wait) proved the default budget is not always enough.
    details.waitForLiveFocusStep("block until cancelled", Duration.ofSeconds(75));
    List<Map<String, Object>> liveViolations = AccessibilityAudit.run(page);
    assertThat(liveViolations).as(AccessibilityAudit.summarize(liveViolations)).isEmpty();

    details.waitForStatus("FAILED", Duration.ofSeconds(60));
    details.expandSteps(
        "Deliberately fails its third step, for step/failure/artifact drill-down verification");
    // A non-empty search still narrows the table to something, but every control (filters, the
    // expanded failure workspace itself) stays on-screen at the same time.
    details.testSearchInput().fill("fail");

    List<Map<String, Object>> terminalViolations = AccessibilityAudit.run(page);
    assertThat(terminalViolations).as(AccessibilityAudit.summarize(terminalViolations)).isEmpty();
  }
}
