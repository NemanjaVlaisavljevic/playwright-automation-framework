package dev.vlaisanem.automation.ui.components;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

/** The message detail popup opened by clicking a row on the admin messages page. */
public final class MessageDetailModal {
  private final Locator container;

  public MessageDetailModal(Page page) {
    container = page.locator("[data-testid='message']");
  }

  public MessageDetailModal assertMatches(
      String name, String phone, String email, String subject, String body) {
    assertThat(container).isVisible();
    assertThat(container).containsText("From: " + name);
    assertThat(container).containsText("Phone: " + phone);
    assertThat(container).containsText("Email: " + email);
    assertThat(container).containsText(subject);
    assertThat(container).containsText(body);
    return this;
  }

  public void close() {
    container.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Close")).click();
  }
}
