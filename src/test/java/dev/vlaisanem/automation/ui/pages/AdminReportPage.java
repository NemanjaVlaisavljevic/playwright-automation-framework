package dev.vlaisanem.automation.ui.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import dev.vlaisanem.automation.api.ApiResult;
import java.net.URI;

/**
 * The admin report/booking calendar at {@code /admin/report}, built on react-big-calendar. Its
 * month view lays out event boxes and day-number cells as separate, position-matched DOM trees
 * (confirmed live: an event's {@code .rbc-row-segment} is not a descendant of its day's {@code
 * .rbc-date-cell}) - there is no reliable containment-based locator to prove an event renders on a
 * specific day. {@link #openAndCaptureReport()} instead captures the exact {@code /api/report} data
 * the calendar itself fetched and rendered from, so date correctness is verified against that data
 * rather than guessed from calendar-grid pixel/column position.
 */
public final class AdminReportPage {
  private final Page page;

  public AdminReportPage(Page page) {
    this.page = page;
  }

  /** Navigates to the report page and returns the exact report-events response it fetched. */
  public ApiResult openAndCaptureReport() {
    Response response =
        page.waitForResponse(
            candidate ->
                "GET".equals(candidate.request().method())
                    && "/api/report".equals(URI.create(candidate.url()).getPath()),
            () -> page.navigate("/admin/report"));
    return new ApiResult(response.status(), response.headers(), response.text());
  }

  /** Asserts an event box with this exact title text is visible in the currently-shown month. */
  public AdminReportPage assertEventVisible(String eventText) {
    assertThat(page.locator(".rbc-event", new Page.LocatorOptions().setHasText(eventText)))
        .isVisible();
    return this;
  }
}
