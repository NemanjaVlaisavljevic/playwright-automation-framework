package dev.vlaisanem.automation.tests.journey;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Page;
import dev.vlaisanem.automation.api.ApiContextFactory;
import dev.vlaisanem.automation.api.AuthClient;
import dev.vlaisanem.automation.api.MessageClient;
import dev.vlaisanem.automation.config.TestConfig;
import dev.vlaisanem.automation.core.AutomationTest;
import dev.vlaisanem.automation.core.Steps;
import dev.vlaisanem.automation.data.ManagedMessage;
import dev.vlaisanem.automation.model.AuthCredentials;
import dev.vlaisanem.automation.model.MessageRequest;
import dev.vlaisanem.automation.ui.pages.AdminLoginPage;
import dev.vlaisanem.automation.ui.pages.AdminMessagesPage;
import dev.vlaisanem.automation.ui.pages.AdminRoomsPage;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Admin message view + delete through the UI. Delete (and mark-as-read) only works against the
 * local Docker target ({@code localTest}) - the public host's message DELETE/mark-as-read return
 * 403 due to a broken internal auth call between its microservices (see infra/rbp/README.md and the
 * message API discovery notes). Run this test class only against a local {@code baseUrl}.
 */
@AutomationTest
@Tag("journey")
@Tag("regression")
@Tag("message")
@Tag("mutation")
@ResourceLock("restful-booker-platform-mutations")
@Epic("Administration")
@Feature("Message management")
class AdminMessageManagementJourneyTest {

  @Test
  @DisplayName("Admin can view and delete a message through the admin UI (local target only)")
  void adminCanViewAndDeleteMessageThroughUi(
      Page page,
      APIRequestContext request,
      ApiContextFactory apiContexts,
      TestConfig config,
      Steps steps) {
    String token =
        steps.call(
            "Authenticate as admin",
            () ->
                new AuthClient(request)
                    .loginAndReadToken(
                        new AuthCredentials(config.adminUsername(), config.adminPassword()))
                    .token());
    MessageClient messages = new MessageClient(apiContexts.withCookie("token", token));

    String suffix = UUID.randomUUID().toString().substring(0, 8);
    MessageRequest requested =
        new MessageRequest(
            "Portfolio Guest",
            "portfolio.guest@example.com",
            "07123456789",
            "Portfolio admin message " + suffix,
            "This message was created by the opt-in portfolio automation suite to verify the"
                + " admin message view/delete UI flow against the local target.");
    try (ManagedMessage message =
        steps.call("Provision a message", () -> ManagedMessage.create(messages, requested))) {
      steps.run(
          "Log in to the admin UI",
          () -> {
            new AdminLoginPage(page).open().loginAs(config.adminUsername(), config.adminPassword());
            new AdminRoomsPage(page).assertLoaded();
          });

      AdminMessagesPage messagesPage =
          steps.call("Open the messages page", () -> new AdminMessagesPage(page).open());

      steps.run("Verify message listed", () -> messagesPage.assertListed(requested.subject()));

      steps.run(
          "View and verify message detail",
          () ->
              messagesPage
                  .open(requested.subject())
                  .assertMatches(
                      requested.name(),
                      requested.phone(),
                      requested.email(),
                      requested.subject(),
                      requested.description())
                  .close());

      steps.run(
          "Delete the message through the admin UI",
          () -> messagesPage.delete(requested.subject()));

      steps.run(
          "Verify message deletion",
          () -> {
            messagesPage.assertNotListed(requested.subject());
            // Empirically confirmed against the running app (not assumed): GET on a deleted message
            // returns 500, not 404 - same behavior already found and documented for rooms in
            // AdminRoomManagementJourneyTest. Asserting the actual observed status, not the "should
            // be" one, per this project's rule against hardening unverified assumptions.
            assertThat(messages.getMessage(message.messageId()).status()).isEqualTo(500);
          });

      message.release();
    }
  }
}
