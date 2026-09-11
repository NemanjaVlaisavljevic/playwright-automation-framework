package dev.vlaisanem.automation.ui.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import dev.vlaisanem.automation.api.ApiResult;
import java.net.URI;

/**
 * The report calendar (react-big-calendar) renders events and day cells as separate, non-nested DOM
 * trees, so no locator can prove an event renders on a specific day. {@link
 * #openAndCaptureReport()} instead captures the {@code /api/report} response the calendar fetched.
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
