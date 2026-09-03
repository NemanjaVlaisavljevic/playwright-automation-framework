package dev.vlaisanem.automation.tests.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIRequestContext;
import dev.vlaisanem.automation.api.ApiResult;
import dev.vlaisanem.automation.api.MessageClient;
import dev.vlaisanem.automation.core.AutomationTest;
import dev.vlaisanem.automation.core.Steps;
import dev.vlaisanem.automation.model.MessageAck;
import dev.vlaisanem.automation.model.MessageRequest;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Tagged {@code mutation}: the message API has no delete endpoint, so a valid submission leaves a
 * record in the shared inbox until the sandbox resets.
 */
@AutomationTest
@Tag("api")
@Tag("regression")
@Tag("message")
@Tag("mutation")
@ResourceLock("restful-booker-platform-mutations")
@Epic("Guest communication")
@Feature("Contact message submission")
class MessageSubmissionApiTest {

  @Test
  @DisplayName("A guest can submit a valid contact message")
  void guestCanSubmitValidMessage(APIRequestContext request, Steps steps) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    MessageRequest message =
        new MessageRequest(
            "Portfolio Guest",
            "portfolio.guest@example.com",
            "07123456789",
            "Portfolio automation subject " + suffix,
            "This message was submitted by the opt-in portfolio automation suite to verify the"
                + " contact API accepts a well-formed request.");

    ApiResult response =
        steps.call(
            "Submit a valid contact message",
            () -> new MessageClient(request).sendMessage(message));

    steps.run(
        "Verify message accepted",
        () -> {
          assertThat(response.status()).isEqualTo(200);
          assertThat(response.bodyAs(MessageAck.class).success()).isTrue();
        });
  }
}
