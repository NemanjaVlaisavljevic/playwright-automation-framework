package dev.vlaisanem.automation.ui.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import java.util.regex.Pattern;

public final class AdminRoomsPage {
  private final Page page;
  private final Locator logout;

  public AdminRoomsPage(Page page) {
    this.page = page;
    logout = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Logout"));
  }

  public AdminRoomsPage assertLoaded() {
    assertThat(page).hasURL(Pattern.compile("/admin/rooms"));
    assertThat(logout).isVisible();
    return this;
  }
}
