package dev.vlaisanem.automation.api;

import com.microsoft.playwright.APIRequest;
import com.microsoft.playwright.APIRequestContext;
import dev.vlaisanem.automation.config.TestConfig;
import dev.vlaisanem.automation.support.JsonSupport;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Creates isolated API sessions and owns their lifecycle for one test. */
public final class ApiContextFactory implements AutoCloseable {

  private final APIRequest apiRequest;
  private final TestConfig config;
  private final List<APIRequestContext> contexts = new ArrayList<>();

  public ApiContextFactory(APIRequest apiRequest, TestConfig config) {
    this.apiRequest = apiRequest;
    this.config = config;
  }

  public APIRequestContext anonymous() {
    return manage(apiRequest.newContext(baseOptions()));
  }

  public APIRequestContext withCookie(String name, String value) {
    if (name == null || name.isBlank() || value == null || value.isBlank()) {
      throw new IllegalArgumentException("Cookie name and value must not be blank");
    }

    URI baseUri = URI.create(config.baseUrl());
    Map<String, Object> cookie = getCookie(name, value, baseUri);
    String storageState =
        JsonSupport.write(Map.of("cookies", List.of(cookie), "origins", List.of()));

    return manage(apiRequest.newContext(baseOptions().setStorageState(storageState)));
  }

  private static Map<String, Object> getCookie(String name, String value, URI baseUri) {
    String domain = baseUri.getHost();
    if (domain == null) {
      throw new IllegalArgumentException("baseUrl must contain a valid host");
    }

    return Map.of(
        "name",
        name,
        "value",
        value,
        "domain",
        domain,
        "path",
        "/",
        "expires",
        -1,
        "httpOnly",
        false,
        "secure",
        "https".equalsIgnoreCase(baseUri.getScheme()),
        "sameSite",
        "Lax");
  }

  @Override
  public void close() {
    for (int index = contexts.size() - 1; index >= 0; index--) {
      contexts.get(index).dispose();
    }
    contexts.clear();
  }

  private APIRequest.NewContextOptions baseOptions() {
    return new APIRequest.NewContextOptions()
        .setBaseURL(config.baseUrl())
        .setTimeout(config.navigationTimeout().toMillis())
        .setExtraHTTPHeaders(Map.of("Accept", "application/json"));
  }

  private APIRequestContext manage(APIRequestContext context) {
    contexts.add(context);
    return context;
  }
}
