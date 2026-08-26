package dev.vlaisanem.automation.ui.components;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;

public final class ContactForm {
  private final Locator name;
  private final Locator email;
  private final Locator phone;
  private final Locator subject;
  private final Locator message;
  private final Locator submit;

  public ContactForm(Page page) {
    Locator section =
        page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Send Us a Message"));
    Locator form = section.locator("xpath=following::form[1]");
    name = form.getByRole(AriaRole.TEXTBOX, new Locator.GetByRoleOptions().setName("Name"));
    email = form.getByRole(AriaRole.TEXTBOX, new Locator.GetByRoleOptions().setName("Email"));
    phone = form.getByRole(AriaRole.TEXTBOX, new Locator.GetByRoleOptions().setName("Phone"));
    subject = form.getByRole(AriaRole.TEXTBOX, new Locator.GetByRoleOptions().setName("Subject"));
    // The page's "Message" <label> targets a "message" id that the textarea does not have
    // (its real id is "description"), so it has no accessible name. Fall back to its test id.
    message = form.getByTestId("ContactDescription");
    submit = form.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Submit"));
  }

  public ContactForm fill(
      String senderName, String senderEmail, String senderPhone, String topic, String body) {
    name.fill(senderName);
    email.fill(senderEmail);
    phone.fill(senderPhone);
    subject.fill(topic);
    message.fill(body);
    return this;
  }

  public void submit() {
    submit.click();
  }
}
