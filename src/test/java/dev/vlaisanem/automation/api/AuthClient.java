package dev.vlaisanem.automation.api;

import com.microsoft.playwright.APIRequestContext;
import dev.vlaisanem.automation.model.AuthCredentials;
import dev.vlaisanem.automation.model.AuthToken;

public final class AuthClient extends BaseApiClient {
  private static final String LOGIN_PATH = "/api/auth/login";

  public AuthClient(APIRequestContext request) {
    super(request);
  }

  public ApiResult login(AuthCredentials credentials) {
    return post(LOGIN_PATH, credentials);
  }

  public AuthToken loginAndReadToken(AuthCredentials credentials) {
    return login(credentials).bodyAs(AuthToken.class);
  }
}
