package dev.vlaisanem.automation.dashboarde2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import dev.vlaisanem.automation.dashboarde2e.pages.RunDetailsPage;
import dev.vlaisanem.automation.dashboarde2e.pages.RunsListPage;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * C5.1's keyboard-operability regression gate - {@code AccessibilityE2eTest}'s axe audit checks
 * static ARIA/contrast/semantic properties, not actual keyboard operability, so it never actually
 * proves any of this. Deliberately does <b>not</b> assert a full page-wide Tab-order snapshot (that
 * would break on every unrelated layout change for no real safety benefit) - instead proves a small
 * set of concrete, real keyboard-driven interactions a viewer genuinely relies on: Tab order
 * through the C4.4 filter toolbar, and Enter/Space activating a disclosure or a Live Focus item. A
 * <b>representative</b> subset of those elements (not all of them) also gets a real focus-indicator
 * style assertion - see the two helper methods' own Javadoc for exactly why: Chromium's own
 * `:focus-visible` heuristic does not fire for a script-driven {@code Locator#focus()} call, only
 * for a genuine keyboard `Tab` (or a click on a text input), so proving it on every element here
 * would require exactly the fragile full-page Tab-order chain this test deliberately avoids.
 */
@ExtendWith(DashboardE2eEnvironment.class)
class KeyboardNavigationE2eTest {

  private static final String BLOCKING_TEST_NAME =
      "Deliberately blocks mid-step, for cancellation/INTERRUPTED reconciliation verification";
  private static final String FAILING_TEST_NAME =
      "Deliberately fails its third step, for step/failure/artifact drill-down verification";

  @Test
  @Timeout(180)
  void tabOrderAndKeyboardActivationWorkAcrossTheFailureWorkspace(Page page) {
    RunsListPage runsList =
        RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL).selectSuite("FIXTURE");
    RunDetailsPage details = runsList.launchRun();

    // An explicit 75s budget, not Playwright's 30s default - see AccessibilityE2eTest's identical
    // wait for the full reasoning (a real review-round failure proved the default isn't always
    // enough for a cold Gradle/JUnit start).
    details.waitForLiveFocusStep("block until cancelled", Duration.ofSeconds(75));
    Locator blockingLiveFocusItem =
        details
            .liveFocusPanel()
            .getByRole(AriaRole.LIST)
            .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(BLOCKING_TEST_NAME));

    // Enter on a Live Focus item reveals and focuses that test's own row in the Tests table. The
    // revealed row's own indicator is checked (a representative, non-:focus-visible case - see
    // assertVisibleBackgroundFocusIndicator); the button's own indicator is not, since reaching it
    // via Locator#focus() rather than a real Tab keypress would make that specific check
    // meaningless (see the class Javadoc).
    blockingLiveFocusItem.press("Enter");
    Locator blockingTestRow = details.testRow(BLOCKING_TEST_NAME);
    assertThat(blockingTestRow).isFocused();
    assertVisibleBackgroundFocusIndicator(page);

    // Space must activate it too, not just Enter - move focus away first so this is a fresh
    // activation, not evidence left over from the Enter press above.
    details.rawPage().keyboard().press("Tab");
    assertThat(blockingTestRow).not().isFocused();
    blockingLiveFocusItem.press("Space");
    assertThat(blockingTestRow).isFocused();
    assertVisibleBackgroundFocusIndicator(page);

    details.waitForStatus("FAILED", Duration.ofSeconds(60));

    // Enter on a test's own disclosure toggle expands its step list.
    details
        .rawPage()
        .getByRole(
            AriaRole.BUTTON, new Page.GetByRoleOptions().setName(FAILING_TEST_NAME).setExact(true))
        .press("Enter");
    assertThat(details.stepRow("intentionally fail this step")).isVisible();

    // A failure detail's native <details><summary> opens via the keyboard alone - real browser
    // default behavior for a focused <summary>, not custom JS.
    Locator viewFullDetail =
        details
            .stepRow("intentionally fail this step")
            .getByText("View full detail", new Locator.GetByTextOptions().setExact(true));
    viewFullDetail.press("Enter");
    assertThat(details.stepRow("intentionally fail this step").locator("pre")).isVisible();

    // Tab order through the C4.4 filter toolbar: search -> status -> evidence -> clear filters,
    // with a focus-indicator check at every stop, not just a representative couple of them.
    details.testSearchInput().click();
    assertThat(details.testSearchInput()).isFocused();
    assertVisibleBoxShadowFocusIndicator(page);

    details.rawPage().keyboard().press("Tab");
    assertThat(details.testStatusFilter()).isFocused();
    assertVisibleBoxShadowFocusIndicator(page);

    details.rawPage().keyboard().press("Tab");
    assertThat(details.testEvidenceFilter()).isFocused();
    assertVisibleBoxShadowFocusIndicator(page);

    details.rawPage().keyboard().press("Tab");
    assertThat(details.clearFiltersButton()).isFocused();
    assertVisibleBoxShadowFocusIndicator(page);
  }

  /**
   * Most interactive elements use the app-wide {@code :focus-visible} convention (see {@code
   * global.css}), rendered as a {@code box-shadow} built from the {@code --focus-ring} token. Only
   * called after focus was established via a real {@code Tab} keypress (or a click on a text input,
   * which Chromium also treats as focus-visible) - a script-driven {@code Locator#focus()} does not
   * count as keyboard-triggered by Chromium's own heuristic, so {@code :focus-visible} never
   * matches and this assertion would fail even when the feature works correctly. Confirmed real by
   * deliberately deleting {@code global.css}'s {@code box-shadow: var(--focus-ring)} line and
   * re-running - this assertion caught it immediately.
   */
  @SuppressWarnings("unchecked")
  private static void assertVisibleBoxShadowFocusIndicator(Page page) {
    Map<String, Object> style =
        (Map<String, Object>)
            page.evaluate(
                "() => { const cs = getComputedStyle(document.activeElement); return {"
                    + " boxShadow: cs.boxShadow }; }");
    assertThat(style.get("boxShadow"))
        .as("focused element's box-shadow focus indicator")
        .isNotEqualTo("none");
  }

  /**
   * The Live-Focus-revealed table row is the one deliberate exception (see {@code
   * RunDetailsPage.module.css}'s {@code .focusableRow:focus} comment - {@code box-shadow} is
   * unreliable on {@code <tr>} across browsers' table rendering models) - it uses a background
   * color change instead.
   */
  @SuppressWarnings("unchecked")
  private static void assertVisibleBackgroundFocusIndicator(Page page) {
    Map<String, Object> style =
        (Map<String, Object>)
            page.evaluate(
                "() => { const cs = getComputedStyle(document.activeElement); return {"
                    + " backgroundColor: cs.backgroundColor }; }");
    assertThat(style.get("backgroundColor"))
        .as("focused row's background-color focus indicator")
        .isNotEqualTo("rgba(0, 0, 0, 0)");
  }
}
