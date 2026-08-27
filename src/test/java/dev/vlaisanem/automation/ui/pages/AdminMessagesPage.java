package dev.vlaisanem.automation.ui.pages;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import dev.vlaisanem.automation.ui.components.MessageDetailModal;

public final class AdminMessagesPage {
  private final Page page;

  public AdminMessagesPage(Page page) {
    this.page = page;
  }

  public AdminMessagesPage open() {
    page.navigate("/admin/message");
    return this;
  }

  /**
   * Rows are only disambiguated by a positional index (data-testid="message{n}") which is not
   * stable across runs/other data - locate by the message's own subject text instead.
   */
  private Locator rowFor(String subject) {
    return page.locator(".row.detail", new Page.LocatorOptions().setHasText(subject));
  }

  public AdminMessagesPage assertListed(String subject) {
    assertThat(rowFor(subject)).isVisible();
    return this;
  }

  public AdminMessagesPage assertNotListed(String subject) {
    assertThat(rowFor(subject)).hasCount(0);
    return this;
  }

  public MessageDetailModal open(String subject) {
    rowFor(subject).click();
    return new MessageDetailModal(page);
  }

  /** Deletes only the row matching {@code subject} - never a page-global first match. */
  public AdminMessagesPage delete(String subject) {
    rowFor(subject).locator("[data-testid^='DeleteMessage']").click();
    return this;
  }
}
