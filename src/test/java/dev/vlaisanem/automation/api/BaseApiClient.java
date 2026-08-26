package dev.vlaisanem.automation.api;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.options.RequestOptions;
import dev.vlaisanem.automation.support.JsonSupport;
import io.qameta.allure.Allure;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseApiClient {
  private static final Logger LOGGER = LoggerFactory.getLogger(BaseApiClient.class);

  private final APIRequestContext request;

  protected BaseApiClient(APIRequestContext request) {
    this.request = request;
  }

  protected ApiResult get(String path) {
    return capture("GET", path, null, () -> request.get(path));
  }

  protected ApiResult post(String path, Object body) {
    return post(path, body, Map.of());
  }

  protected ApiResult post(String path, Object body, Map<String, String> headers) {
    String json = JsonSupport.write(body);
    RequestOptions options =
        RequestOptions.create().setData(json).setHeader("Content-Type", "application/json");
    headers.forEach(options::setHeader);
    return capture("POST", path, json, () -> request.post(path, options));
  }

  protected ApiResult put(String path, Object body, Map<String, String> headers) {
    String json = JsonSupport.write(body);
    RequestOptions options =
        RequestOptions.create().setData(json).setHeader("Content-Type", "application/json");
    headers.forEach(options::setHeader);
    return capture("PUT", path, json, () -> request.put(path, options));
  }

  protected ApiResult put(String path, Object body) {
    return put(path, body, Map.of());
  }

  protected ApiResult delete(String path, Map<String, String> headers) {
    RequestOptions options = RequestOptions.create();
    headers.forEach(options::setHeader);
    return capture("DELETE", path, null, () -> request.delete(path, options));
  }

  protected ApiResult delete(String path) {
    return delete(path, Map.of());
  }

  private static ApiResult capture(
      String method, String path, String requestBody, Supplier<APIResponse> call) {
    Instant started = Instant.now();
    APIResponse response = call.get();
    try {
      String responseBody = response.text();
      ApiResult result =
          new ApiResult(
              response.status(),
              new LinkedHashMap<>(response.headers()),
              responseBody == null ? "" : responseBody);
      long durationMs = Duration.between(started, Instant.now()).toMillis();
      LOGGER.info("{} {} -> {} ({} ms)", method, path, result.status(), durationMs);
      attachExchange(method, path, requestBody, result, durationMs);
      return result;
    } finally {
      response.dispose();
    }
  }

  private static void attachExchange(
      String method, String path, String requestBody, ApiResult result, long durationMs) {
    StringBuilder attachment = new StringBuilder();
    attachment
        .append(method)
        .append(' ')
        .append(path)
        .append('\n')
        .append("Status: ")
        .append(result.status())
        .append('\n')
        .append("Duration: ")
        .append(durationMs)
        .append(" ms\n");
    if (requestBody != null) {
      attachment.append("\nRequest:\n").append(JsonSupport.redact(requestBody)).append('\n');
    }
    attachment.append("\nResponse:\n").append(JsonSupport.redact(result.body()));
    Allure.addAttachment(method + " " + path, "text/plain", attachment.toString());
  }
}
