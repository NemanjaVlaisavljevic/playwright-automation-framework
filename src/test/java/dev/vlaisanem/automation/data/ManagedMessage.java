package dev.vlaisanem.automation.data;

import dev.vlaisanem.automation.api.ApiResult;
import dev.vlaisanem.automation.api.MessageClient;
import dev.vlaisanem.automation.model.MessageRequest;
import dev.vlaisanem.automation.model.MessageSummary;
import dev.vlaisanem.automation.model.MessagesResponse;

/** A test-owned message that is deleted automatically when its scope ends. */
public final class ManagedMessage implements AutoCloseable {
  private final MessageClient messages;
  private final int messageId;
  private boolean closed;

  private ManagedMessage(MessageClient messages, int messageId) {
    this.messages = messages;
    this.messageId = messageId;
  }

  public static ManagedMessage create(MessageClient messages, MessageRequest request) {
    ApiResult creation = messages.sendMessage(request);
    Integer createdMessageId = null;

    try {
      requireStatus(creation, 200, "create test message");
      createdMessageId = findMessageId(messages, request.subject());
      if (createdMessageId == null) {
        throw new AssertionError("Created message was not returned by the API");
      }
      return new ManagedMessage(messages, createdMessageId);
    } catch (RuntimeException | AssertionError failure) {
      if (createdMessageId == null && creation.isSuccessful()) {
        try {
          createdMessageId = findMessageId(messages, request.subject());
        } catch (RuntimeException lookupFailure) {
          failure.addSuppressed(lookupFailure);
        }
      }
      if (createdMessageId != null) {
        cleanupAfterSetupFailure(messages, createdMessageId, failure);
      }
      throw failure;
    }
  }

  private static Integer findMessageId(MessageClient messages, String subject) {
    return messages.getMessages().bodyAs(MessagesResponse.class).messages().stream()
        .filter(message -> message.subject().equals(subject))
        .map(MessageSummary::id)
        .findFirst()
        .orElse(null);
  }

  public int messageId() {
    return messageId;
  }

  /**
   * Marks this message as already handled (e.g. the test itself deleted it via the UI as its main
   * action), so {@link #close()} does not also try to delete an already-deleted message.
   */
  public void release() {
    closed = true;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    requireStatus(messages.deleteMessage(messageId), 202, "delete test message " + messageId);
  }

  private static void cleanupAfterSetupFailure(
      MessageClient messages, int messageId, Throwable originalFailure) {
    try {
      requireStatus(messages.deleteMessage(messageId), 202, "clean up test message " + messageId);
    } catch (RuntimeException | AssertionError cleanupFailure) {
      originalFailure.addSuppressed(cleanupFailure);
    }
  }

  private static void requireStatus(ApiResult response, int expected, String operation) {
    if (response.status() != expected) {
      throw new AssertionError(
          operation + " returned " + response.status() + " instead of " + expected);
    }
  }
}
