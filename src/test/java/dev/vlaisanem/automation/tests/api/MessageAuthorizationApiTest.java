package dev.vlaisanem.automation.tests.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIRequestContext;
import dev.vlaisanem.automation.api.MessageClient;
import dev.vlaisanem.automation.core.AutomationTest;
import dev.vlaisanem.automation.core.Steps;
import dev.vlaisanem.automation.model.MessageDetails;
import dev.vlaisanem.automation.model.MessagesResponse;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tagged {@code known-defect}: unlike bookings (see {@link BookingAuthorizationApiTest}), the
 * message API has no authorization check on any read endpoint, so an anonymous caller can list and
 * read every guest's contact details.
 */
@AutomationTest
@Tag("api")
@Tag("regression")
@Tag("message")
@Tag("read-only")
@Tag("known-defect")
@Epic("Guest communication")
@Feature("Message access control")
class MessageAuthorizationApiTest {

  @Test
  @DisplayName("Known gap: an anonymous guest can list every contact message")
  void anonymousCanListAllMessages(APIRequestContext request, Steps steps) {
    MessagesResponse messages =
        steps.call(
            "List all messages anonymously",
            () -> new MessageClient(request).getMessages().bodyAs(MessagesResponse.class));

    steps.run(
        "Verify messages are readable without authentication (known gap)",
        () -> assertThat(messages.messages()).isNotEmpty());
  }

  @Test
  @DisplayName("Known gap: an anonymous guest can read another guest's email and phone number")
  void anonymousCanReadAnyMessagesPersonalDetails(APIRequestContext request, Steps steps) {
    // The shared public demo target's own message ids drift over time (messages get added/removed
    // by other tests/users) - a hardcoded id would eventually stop existing. Picking the first id
    // off a real, current listing keeps this test pinned to whatever the target actually has right
    // now, rather than assuming a specific id still exists.
    MessagesResponse messages =
        steps.call(
            "List all messages anonymously",
            () -> new MessageClient(request).getMessages().bodyAs(MessagesResponse.class));
    assertThat(messages.messages())
        .as("shared demo target should have at least one message to read")
        .isNotEmpty();
    int messageId = messages.messages().get(0).id();

    MessageDetails message =
        steps.call(
            "Read a message's details anonymously",
            () -> new MessageClient(request).getMessage(messageId).bodyAs(MessageDetails.class));

    steps.run(
        "Verify personal details are readable without authentication (known gap)",
        () -> {
          assertThat(message.email()).isNotBlank();
          assertThat(message.phone()).isNotBlank();
        });
  }
}
