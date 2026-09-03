package dev.vlaisanem.automation.tests.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import dev.vlaisanem.automation.core.AutomationTest;
import dev.vlaisanem.automation.core.Steps;
import dev.vlaisanem.automation.model.MessageAck;
import dev.vlaisanem.automation.support.JsonSupport;
import dev.vlaisanem.automation.ui.components.ContactForm;
import dev.vlaisanem.automation.ui.pages.HomePage;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Tagged {@code mutation}: the message API has no delete endpoint, so submitting the form leaves a
 * record in the shared inbox until the sandbox resets.
 */
@AutomationTest
@Tag("ui")
@Tag("regression")
@Tag("message")
@Tag("mutation")
@ResourceLock("restful-booker-platform-mutations")
@Epic("Guest communication")
@Feature("Contact form")
class ContactFormTest {

  @Test
  @DisplayName("Guest can submit the contact form and the API accepts it")
  void guestCanSubmitContactForm(Page page, Steps steps) {
    steps.run("Open homepage", () -> new HomePage(page).open().assertLoaded());
    ContactForm form = new ContactForm(page);
    String suffix = UUID.randomUUID().toString().substring(0, 8);

    Response response =
        steps.call(
            "Submit the contact form",
            () ->
                page.waitForResponse(
                    candidate -> candidate.url().contains("/api/message"),
                    () -> {
                      form.fill(
                          "Portfolio Guest",
                          "portfolio.guest@example.com",
                          "07123456789",
                          "Portfolio UI subject " + suffix,
                          "This message was submitted through the UI by the opt-in portfolio"
                              + " automation suite.");
                      form.submit();
                    }));

    steps.run(
        "Verify contact API accepted the message",
        () -> {
          assertThat(response.status()).isEqualTo(200);
          assertThat(JsonSupport.read(response.text(), MessageAck.class).success()).isTrue();
        });
  }
}
