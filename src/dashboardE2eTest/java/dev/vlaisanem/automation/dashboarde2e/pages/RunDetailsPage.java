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

  /**
   * Exact match: {@code getByRole}'s default substring matching would otherwise also resolve a
   * disclosure-toggle button whose accessible name happens to contain "Cancel" as a substring (e.g.
   * a test named "...for cancellation/INTERRUPTED reconciliation verification"), a real strict-mode
   * violation this project's own fixtures ran into.
   */
  public Locator cancelButton() {
    return page.getByRole(
        AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Cancel").setExact(true));
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

  /**
   * A test row with any steps renders its name as a disclosure toggle, collapsed by default - its
   * step list is not even in the DOM until expanded. Clicks the toggle by its accessible name
   * (exact match: a substring match could otherwise resolve two different test names where one is a
   * prefix of the other).
   */
  public void expandSteps(String testDisplayName) {
    page.getByRole(
            AriaRole.BUTTON, new Page.GetByRoleOptions().setName(testDisplayName).setExact(true))
        .click();
  }

  /**
   * A step's own {@code <li>} row (see {@code RunDetailsPage.tsx}'s {@code StepRow}) - status
   * badge, name, any artifact links, and an optional {@code Detail} disclosure, all scoped to this
   * one step so a caller never has to worry about a same-named element elsewhere on the page.
   */
  public Locator stepRow(String stepName) {
    return page.getByRole(AriaRole.LISTITEM)
        .filter(new Locator.FilterOptions().setHasText(stepName));
  }

  /**
   * Expands the step's {@code <details><summary>Detail</summary>...} disclosure (a real native
   * toggle - collapsed by default, so its {@code <pre>} content must be opened before an assertion
   * that requires it visible, not just present in the DOM) and returns the failure detail text
   * inside it.
   */
  public String stepDetailText(String stepName) {
    Locator row = stepRow(stepName);
    row.getByText("Detail").click();
    return row.locator("pre").textContent();
  }

  /** A step-scoped artifact link (e.g. {@code "Screenshot"} or {@code "Trace"}). */
  public Locator stepArtifactLink(String stepName, String artifactLabel) {
    return stepRow(stepName)
        .getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName(artifactLabel));
  }

  public void waitForConnectionState(String expectedText, Duration timeout) {
    assertThat(connectionBanner())
        .hasText(
            expectedText, new LocatorAssertions.HasTextOptions().setTimeout(timeout.toMillis()));
  }

  /**
   * Any status badge (test-level or step-level) still reading exactly {@code "RUNNING"} - once the
   * run itself is terminal, this must always be empty. Case-sensitive exact match: the "Running"
   * {@code MetricCard} label and the "Run finished." banner text both differ only in case, so an
   * unscoped case-insensitive search would false-positive on either.
   */
  public Locator anyRunningStatusBadge() {
    return page.getByText("RUNNING", new Page.GetByTextOptions().setExact(true));
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
