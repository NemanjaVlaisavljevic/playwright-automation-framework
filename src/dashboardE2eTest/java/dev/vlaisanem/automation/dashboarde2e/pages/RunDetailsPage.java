package dev.vlaisanem.automation.dashboarde2e.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.AriaRole;
import java.time.Duration;

/** {@code /runs/:runId} - header details, connection banner, progress, and the tests table. */
public final class RunDetailsPage {

  private final Page page;

  RunDetailsPage(Page page) {
    this.page = page;
  }

  public static RunDetailsPage at(Page page, String baseUrl, String runId) {
    page.navigate(baseUrl + "/runs/" + runId);
    return new RunDetailsPage(page);
  }

  public String runId() {
    String url = page.url();
    return url.substring(url.lastIndexOf('/') + 1);
  }

  /**
   * Scoped to {@code <main>}: {@code AppShell}'s own health indicator in the page header is
   * <i>also</i> {@code role="status"} - an unscoped {@code getByRole(STATUS)} matches both.
   */
  public Locator connectionBanner() {
    return page.locator("main").getByRole(AriaRole.STATUS);
  }

  /**
   * Scoped via the {@code <dt>Status</dt><dd>...} structure, not a plain text lookup: a run whose
   * own status happens to be "RUNNING" (or "FAILED") would otherwise collide with a test row in the
   * tests table below sharing that same word as its own (test-level) status.
   */
  public Locator statusValue() {
    return page.locator("dt", new Page.LocatorOptions().setHasText("Status")).locator("+ dd");
  }

  public Locator cancelButton() {
    return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Cancel"));
  }

  public Locator downloadLogLink() {
    return page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Download log"));
  }

  public Locator backToRunsLink() {
    return page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Back to runs"));
  }

  public Locator metricValue(String label) {
    // MetricCard renders <p>{value}</p><p>{label}</p> - the value is always the label's
    // immediately-preceding sibling (see MetricCard.tsx).
    return page.locator("p", new Page.LocatorOptions().setHasText(label))
        .locator("xpath=preceding-sibling::p[1]");
  }

  /**
   * Reads a metric's value as a number, not text - a caller checking "is this greater than zero"
   * must not use {@code Locator}'s own {@code hasText("0")}, whose substring semantics would match
   * "10" just as readily as "0" itself.
   */
  public int metricNumber(String label) {
    return Integer.parseInt(metricValue(label).textContent().trim());
  }

  public Locator testRow(String testDisplayName) {
    return page.getByRole(AriaRole.ROW)
        .filter(new Locator.FilterOptions().setHasText(testDisplayName));
  }

  public void waitForConnectionState(String expectedText, Duration timeout) {
    assertThat(connectionBanner())
        .hasText(
            expectedText, new LocatorAssertions.HasTextOptions().setTimeout(timeout.toMillis()));
  }

  public void waitForStatus(String expectedStatus, Duration timeout) {
    assertThat(statusValue())
        .hasText(
            expectedStatus, new LocatorAssertions.HasTextOptions().setTimeout(timeout.toMillis()));
  }

  public Page rawPage() {
    return page;
  }
}
