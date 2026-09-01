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

  public RunDetailsPage launchRun() {
    launchForm().getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Run")).click();
    page.waitForURL("**/runs/*");
    return new RunDetailsPage(page);
  }

  public Page rawPage() {
    return page;
  }
}
