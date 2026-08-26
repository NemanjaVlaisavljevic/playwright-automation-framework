package dev.vlaisanem.automation.ui.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

public final class AdminLoginPage {
  private final Page page;
  private final Locator username;
  private final Locator password;
  private final Locator login;
  private final Locator invalidCredentials;

  public AdminLoginPage(Page page) {
    this.page = page;
    username = page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName("Username"));
    password = page.getByLabel("Password");
    login = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Login"));
    invalidCredentials = page.getByText("Invalid credentials");
  }

  public AdminLoginPage open() {
    page.navigate("/admin");
    return this;
  }

  public AdminLoginPage loginAs(String user, String secret) {
    username.fill(user);
    password.fill(secret);
    login.click();
    return this;
  }

  public void assertInvalidCredentials() {
    assertThat(invalidCredentials).isVisible();
    assertThat(login).isVisible();
  }
}
