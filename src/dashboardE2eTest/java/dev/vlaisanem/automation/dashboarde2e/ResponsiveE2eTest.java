package dev.vlaisanem.automation.dashboarde2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import dev.vlaisanem.automation.dashboarde2e.pages.RunDetailsPage;
import dev.vlaisanem.automation.dashboarde2e.pages.RunsListPage;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * C4.6's responsive check at 320px (the narrowest width any real phone ships), tablet (768px), and
 * desktop (1440px) - a real Chromium viewport via {@link Page#setViewportSize}, not a DOM/CSS
 * approximation, so this reflects what an actual narrow browser does, including document-level
 * horizontal scroll a component's own {@code overflow-x: auto} wrapper does not always contain.
 */
@ExtendWith(DashboardE2eEnvironment.class)
class ResponsiveE2eTest {

  private static final int[] WIDTHS = {320, 768, 1440};

  @Test
  @Timeout(30)
  void runsListPageNeverScrollsHorizontally(Page page) {
    for (int width : WIDTHS) {
      page.setViewportSize(width, 700);
      RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL);
      assertNoDocumentLevelHorizontalOverflow(page, width);
    }
  }

  @Test
  @Timeout(90)
  void runDetailsPageWithFailureWorkspaceNeverScrollsHorizontally(Page page) {
    RunsListPage runsList =
        RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL).selectSuite("FIXTURE");
    RunDetailsPage details = runsList.launchRun();
    details.waitForStatus("FAILED", Duration.ofSeconds(60));
    details.expandSteps(
        "Deliberately fails its third step, for step/failure/artifact drill-down verification");

    for (int width : WIDTHS) {
      page.setViewportSize(width, 800);
      assertNoDocumentLevelHorizontalOverflow(page, width);
    }
  }

  /**
   * A component's own scrollable table (see {@code RunDetailsPage.module.css}'s {@code
   * .tableScroll}) is expected to carry its own internal horizontal scrollbar when its {@code
   * min-width} exceeds the viewport - that is by design. What must never happen is the <i>document
   * itself</i> gaining horizontal scroll room, which would let a viewer drag the whole page
   * (header, nav, everything) sideways instead of just the one wide table.
   */
  private void assertNoDocumentLevelHorizontalOverflow(Page page, int width) {
    double scrollWidth =
        ((Number) page.evaluate("document.documentElement.scrollWidth")).doubleValue();
    double clientWidth =
        ((Number) page.evaluate("document.documentElement.clientWidth")).doubleValue();
    assertThat(scrollWidth)
        .as(
            "documentElement.scrollWidth (%.0f) vs clientWidth (%.0f) at viewport width %dpx - a"
                + " gap means the whole page can be scrolled horizontally, not just a table's own"
                + " scroll container",
            scrollWidth, clientWidth, width)
        .isLessThanOrEqualTo(clientWidth);
  }
}
