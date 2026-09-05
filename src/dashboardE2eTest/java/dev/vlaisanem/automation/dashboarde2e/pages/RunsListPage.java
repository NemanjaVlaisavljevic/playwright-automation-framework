package dev.vlaisanem.automation.dashboarde2e.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/** {@code /runs} - the launch form plus the runs table. */
public final class RunsListPage {

  private final Page page;

  private RunsListPage(Page page) {
    this.page = page;
  }

  public static RunsListPage open(Page page, String baseUrl) {
    page.navigate(baseUrl + "/runs");
    page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Runs")).waitFor();
    return new RunsListPage(page);
  }

  /**
   * Scoped to the {@code <form>}: {@code RunsTable} also renders its own "Suite" filter {@code
   * <select>} on this same page, so an unscoped {@code getByLabel("Suite")} would be ambiguous.
   */
  private Locator launchForm() {
    return page.locator("form");
  }

  public RunsListPage selectEnvironment(String environment) {
    launchForm().getByLabel("Environment").selectOption(environment);
    return this;
  }

  public RunsListPage selectSuite(String suite) {
    launchForm().getByLabel("Suite").selectOption(suite);
    return this;
  }

  /**
   * Checks one {@code CustomTestPicker} entry by its accessible name, which is the checkbox {@code
   * <label>}'s full text content - {@code test.displayName} followed by its category badge (see
   * {@code CustomTestPicker.tsx}) - so this matches on {@code testDisplayName} as a (default,
   * case-insensitive) substring rather than an exact name. {@code check()} auto-waits for the
   * catalog fetch to resolve and the row to render, so no separate "picker loaded" wait is needed.
   */
  public RunsListPage selectCustomTest(String testDisplayName) {
    launchForm()
        .getByRole(AriaRole.CHECKBOX, new Locator.GetByRoleOptions().setName(testDisplayName))
        .check();
    return this;
  }

  /** The {@code CustomTestPicker}'s own live selection counter, e.g. "2 tests selected". */
  public Locator customSelectionCount() {
    return launchForm().locator("[aria-live='polite']");
  }

  public RunDetailsPage launchRun() {
    launchForm().getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Run")).click();
    page.waitForURL("**/runs/*");
    return new RunDetailsPage(page);
  }

  public Page rawPage() {
    return page;
  }
}
