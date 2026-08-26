package dev.vlaisanem.automation.api;

import com.microsoft.playwright.APIRequestContext;
import dev.vlaisanem.automation.model.MessageRequest;

public final class MessageClient extends BaseApiClient {
  private static final String MESSAGES_PATH = "/api/message";

  public MessageClient(APIRequestContext request) {
    super(request);
  }

  public ApiResult sendMessage(MessageRequest message) {
    return post(MESSAGES_PATH, message);
  }

  public ApiResult getMessages() {
    return get(MESSAGES_PATH);
  }

  public ApiResult getMessage(int messageId) {
    return get(MESSAGES_PATH + "/" + messageId);
  }

  public ApiResult getMessageCount() {
    return get(MESSAGES_PATH + "/count");
  }
}
