package dev.vlaisanem.automation.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.vlaisanem.automation.runner.listener.SensitiveDataKeys;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

public final class JsonSupport {
  private static final Set<String> SENSITIVE_KEYS = SensitiveDataKeys.KEYS;

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  private JsonSupport() {}

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Could not serialize JSON payload", exception);
    }
  }

  public static <T> T read(String value, Class<T> type) {
    try {
      return MAPPER.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(
          "Could not deserialize response as " + type.getSimpleName(), exception);
    }
  }

  public static JsonNode tree(String value) {
    try {
      return MAPPER.readTree(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Response is not valid JSON", exception);
    }
  }

  public static List<String> stringList(String value) {
    JsonNode root = tree(value);
    if (!root.isArray()
        || !StreamSupport.stream(root.spliterator(), false).allMatch(JsonNode::isTextual)) {
      throw new IllegalArgumentException("Response is not a JSON array of strings");
    }
    return StreamSupport.stream(root.spliterator(), false).map(JsonNode::textValue).toList();
  }

  public static String redact(String json) {
    if (json == null || json.isBlank()) {
      return "";
    }
    try {
      JsonNode root = MAPPER.readTree(json);
      redactNode(root);
      return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    } catch (JsonProcessingException exception) {
      return "<non-JSON body omitted>";
    }
  }

  private static void redactNode(JsonNode node) {
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        if (SENSITIVE_KEYS.contains(field.getKey().toLowerCase(Locale.ROOT))) {
          ((com.fasterxml.jackson.databind.node.ObjectNode) node)
              .put(field.getKey(), "***REDACTED***");
        } else {
          redactNode(field.getValue());
        }
      }
    } else if (node.isArray()) {
      node.forEach(JsonSupport::redactNode);
    }
  }
}
