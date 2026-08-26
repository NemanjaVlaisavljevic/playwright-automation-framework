package dev.vlaisanem.automation.api;

import dev.vlaisanem.automation.support.JsonSupport;
import java.util.List;
import java.util.Map;

public record ApiResult(int status, Map<String, String> headers, String body) {
  public boolean isSuccessful() {
    return status >= 200 && status < 300;
  }

  public <T> T bodyAs(Class<T> type) {
    return JsonSupport.read(body, type);
  }

  public List<String> bodyAsStringList() {
    return JsonSupport.stringList(body);
  }

  public boolean hasStatus(int... expectedStatuses) {
    return java.util.Arrays.stream(expectedStatuses).anyMatch(expected -> expected == status);
  }

  public String header(String name) {
    return headers.entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  public List<String> schemaErrors(String schemaResource) {
    return dev.vlaisanem.automation.support.SchemaValidator.validate(schemaResource, body);
  }
}
