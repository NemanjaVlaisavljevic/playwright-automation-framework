package dev.vlaisanem.automation.tests.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIRequestContext;
import dev.vlaisanem.automation.api.ApiResult;
import dev.vlaisanem.automation.api.MessageClient;
import dev.vlaisanem.automation.core.AutomationTest;
import dev.vlaisanem.automation.core.Steps;
import dev.vlaisanem.automation.model.MessageRequest;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * A validation regression can persist a message, so these POST checks are mutation tests on the
 * shared public target even though their expected responses are rejections.
 */
@AutomationTest
@Tag("api")
@Tag("regression")
@Tag("message")
@Tag("mutation")
@ResourceLock("restful-booker-platform-mutations")
@Epic("Guest communication")
@Feature("Contact message validation")
class MessageValidationApiTest {

  @Test
  @DisplayName("An empty message is rejected with a field error for every required property")
  void emptyMessageIsRejected(APIRequestContext request, Steps steps) {
    ApiResult response =
        steps.call(
            "Submit a completely empty message",
            () ->
                new MessageClient(request)
                    .sendMessage(new MessageRequest(null, null, null, null, null)));

    steps.run(
        "Verify a field error for every required property",
        () -> {
          assertThat(response.status()).isEqualTo(400);
          assertThat(response.bodyAsStringList())
              .contains("Name may not be blank")
              .contains("Email may not be blank")
              .contains("Phone may not be blank")
              .contains("Subject may not be blank")
              .contains("Message may not be blank");
        });
  }

  @Test
  @DisplayName("A malformed email address is rejected")
  void malformedEmailIsRejected(APIRequestContext request, Steps steps) {
    ApiResult response =
        steps.call(
            "Submit a message with a malformed email",
            () ->
                new MessageClient(request)
                    .sendMessage(
                        new MessageRequest(
                            "Portfolio Guest",
                            "not-an-email",
                            "07123456789",
                            "A valid subject line",
                            "A message body that is long enough to pass the length checks.")));

    steps.run(
        "Verify email format error",
        () -> {
          assertThat(response.status()).isEqualTo(400);
          assertThat(response.bodyAsStringList()).contains("must be a well-formed email address");
        });
  }

  @Test
  @DisplayName("A subject and message shorter than the minimum length are rejected")
  void tooShortSubjectAndMessageAreRejected(APIRequestContext request, Steps steps) {
    ApiResult response =
        steps.call(
            "Submit a message with a too-short subject and body",
            () ->
                new MessageClient(request)
                    .sendMessage(
                        new MessageRequest(
                            "Portfolio Guest", "guest@example.com", "07123456789", "hi", "short")));

    steps.run(
        "Verify length errors",
        () -> {
          assertThat(response.status()).isEqualTo(400);
          assertThat(response.bodyAsStringList())
              .contains("Subject must be between 5 and 100 characters.")
              .contains("Message must be between 20 and 2000 characters.");
        });
  }

  @Test
  @DisplayName("A phone number shorter than the minimum length is rejected")
  void tooShortPhoneIsRejected(APIRequestContext request, Steps steps) {
    ApiResult response =
        steps.call(
            "Submit a message with a too-short phone number",
            () ->
                new MessageClient(request)
                    .sendMessage(
                        new MessageRequest(
                            "Portfolio Guest",
                            "guest@example.com",
                            "123",
                            "A valid subject line",
                            "A message body that is long enough to pass the length checks.")));

    steps.run(
        "Verify phone length error",
        () -> {
          assertThat(response.status()).isEqualTo(400);
          assertThat(response.bodyAsStringList())
              .contains("Phone must be between 11 and 21 characters.");
        });
  }
}
