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

  /**
   * Scoped to the Tests table specifically, not just any {@code role="row"} on the page: once a
   * test has captured artifacts, the separate Artifacts section below renders its own {@code <tr>}
   * per artifact carrying that same test's display name in its own "Test" column - a real
   * strict-mode violation this project's own E2E run caught the first time a test called this after
   * artifacts existed on the page.
   */
  public Locator testRow(String testDisplayName) {
    return testsTable()
        .getByRole(AriaRole.ROW)
        .filter(new Locator.FilterOptions().setHasText(testDisplayName));
  }

  /** The Tests table itself - see {@code TestResultsTable.tsx}'s own {@code <caption>}. */
  private Locator testsTable() {
    return page.getByRole(AriaRole.TABLE, new Page.GetByRoleOptions().setName("Tests for run"));
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
   * badge, name, any artifact links, and an optional {@code FailureDetail} ("View full detail")
   * disclosure, all scoped to this one step so a caller never has to worry about a same-named
   * element elsewhere on the page.
   */
  public Locator stepRow(String stepName) {
    return page.getByRole(AriaRole.LISTITEM)
        .filter(new Locator.FilterOptions().setHasText(stepName));
  }

  /**
   * Expands the step's {@code <details><summary>View full detail</summary>...} disclosure (a real
   * native toggle - collapsed by default, so its {@code <pre>} content must be opened before an
   * assertion that requires it visible, not just present in the DOM) and returns the failure detail
   * text inside it.
   */
  public String stepDetailText(String stepName) {
    Locator row = stepRow(stepName);
    // Exact match: the UI's own disclosure reads "View full detail" (see FailureDetail.tsx), not
    // just "Detail" - a substring match happens to still resolve today only because nothing else in
    // the row contains that word, but a failure message containing "detail" would silently break
    // it.
    row.getByText("View full detail", new Locator.GetByTextOptions().setExact(true)).click();
    return row.locator("pre").textContent();
  }

  /**
   * A step-scoped artifact link that is the only one of its kind in the row (e.g. {@code "Trace"},
   * which only ever matches the single "Download trace" link). Screenshot has two distinct links in
   * the same row - see {@link #stepScreenshotThumbnailLink(String)} and {@link
   * #stepOpenScreenshotLink(String)} - a plain substring match on "Screenshot" resolves to both and
   * is a real strict-mode violation this project's own E2E run caught.
   */
  public Locator stepArtifactLink(String stepName, String artifactLabel) {
    return stepRow(stepName)
        .getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName(artifactLabel));
  }

  /**
   * The screenshot thumbnail's own link - accessible name comes from the {@code <img>}'s {@code
   * alt}, "Screenshot for {testDisplayName}" (see {@code FailureDetail.tsx}).
   */
  public Locator stepScreenshotThumbnailLink(String stepName) {
    return stepRow(stepName)
        .getByRole(AriaRole.LINK, new Locator.GetByRoleOptions().setName("Screenshot for"));
  }

  /** The separate "Open screenshot" text link next to the thumbnail, same artifact either way. */
  public Locator stepOpenScreenshotLink(String stepName) {
    return stepRow(stepName)
        .getByRole(
            AriaRole.LINK,
            new Locator.GetByRoleOptions().setName("Open screenshot").setExact(true));
  }

  /**
   * The C4.2 "live focus panel" - a named {@code <section>} between Progress and the Tests table
   * (see {@code LiveFocusPanel.tsx}) listing whichever test(s) are currently RUNNING. Present only
   * while the connection is live/reconnecting - gone entirely once the run itself is terminal.
   */
  public Locator liveFocusPanel() {
    return page.getByRole(AriaRole.REGION);
  }

  /**
   * Waits for the given step name to appear as an active-step label inside the Live Focus panel's
   * own list (see {@code LiveFocusPanel.tsx}'s {@code activeStepLabel}) - the deterministic
   * synchronization point tests use to catch the panel genuinely on screen (e.g. {@code
   * CancelDuringStepFixtureTest}'s fixed-duration blocking step), rather than racing arbitrary
   * suite timing. Takes an explicit, generous timeout (not Playwright's 30s default): a cold
   * Gradle/JUnit start, JIT warmup, or backend queue cleanup from a prior test's run can burn a
   * meaningful chunk of an 8s active window before it even begins, and a real review-round failure
   * proved the default budget is not always enough.
   */
  public void waitForLiveFocusStep(String stepName, Duration timeout) {
    liveFocusPanel()
        .getByRole(AriaRole.LIST)
        .getByText(stepName)
        .waitFor(new Locator.WaitForOptions().setTimeout(timeout.toMillis()));
  }

  /**
   * The C4.4 search/status/evidence toolbar above the Tests table - see {@code
   * TestResultsFilters.tsx}. Each control is found by its real {@code <label>}, matching the
   * accessibility contract the component itself guarantees.
   */
  public Locator testSearchInput() {
    return page.getByLabel("Search tests or steps");
  }

  public Locator testStatusFilter() {
    return page.getByLabel("Status");
  }

  public Locator testEvidenceFilter() {
    return page.getByLabel("Evidence");
  }

  public Locator clearFiltersButton() {
    return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Clear filters"));
  }

  /**
   * The C4.5 per-row "Copy link" button - scoped to the test's own row, and matched by its full
   * accessible name (see {@code TestResultRow.tsx}'s own {@code aria-label}) so it can never be
   * confused with a different row's identical-looking "Copy link" visible text.
   */
  public Locator copyTestLinkButton(String testDisplayName) {
    return testRow(testDisplayName)
        .getByRole(
            AriaRole.BUTTON,
            new Locator.GetByRoleOptions().setName("Copy link to test " + testDisplayName));
  }

  /** The C4.5 per-step "Copy link" button - see {@link #copyTestLinkButton(String)}. */
  public Locator copyStepLinkButton(String stepName) {
    return stepRow(stepName)
        .getByRole(
            AriaRole.BUTTON,
            new Locator.GetByRoleOptions().setName("Copy link to step " + stepName));
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
