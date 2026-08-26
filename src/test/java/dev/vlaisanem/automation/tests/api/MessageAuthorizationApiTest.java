package dev.vlaisanem.automation.tests.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIRequestContext;
import dev.vlaisanem.automation.api.MessageClient;
import dev.vlaisanem.automation.core.AutomationTest;
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
  void anonymousCanListAllMessages(APIRequestContext request) {
    MessagesResponse messages =
        new MessageClient(request).getMessages().bodyAs(MessagesResponse.class);

    assertThat(messages.messages()).isNotEmpty();
  }

  @Test
  @DisplayName("Known gap: an anonymous guest can read another guest's email and phone number")
  void anonymousCanReadAnyMessagesPersonalDetails(APIRequestContext request) {
    MessageDetails message = new MessageClient(request).getMessage(1).bodyAs(MessageDetails.class);

    assertThat(message.email()).isNotBlank();
    assertThat(message.phone()).isNotBlank();
  }
}
