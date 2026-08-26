package dev.vlaisanem.automation.tests.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIRequestContext;
import dev.vlaisanem.automation.api.ApiResult;
import dev.vlaisanem.automation.api.AuthClient;
import dev.vlaisanem.automation.config.TestConfig;
import dev.vlaisanem.automation.core.AutomationTest;
import dev.vlaisanem.automation.model.AuthCredentials;
import dev.vlaisanem.automation.model.AuthToken;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@AutomationTest
@Tag("api")
@Tag("smoke")
@Tag("regression")
@Tag("auth")
@Tag("read-only")
@Epic("Administration")
@Feature("Authentication API")
class AuthenticationApiTest {

  @Test
  @DisplayName("Admin can obtain a non-empty session token")
  void adminCanAuthenticate(APIRequestContext request, TestConfig config) {
    ApiResult response =
        new AuthClient(request)
            .login(new AuthCredentials(config.adminUsername(), config.adminPassword()));

    assertThat(response.status()).isEqualTo(200);
    assertThat(response.bodyAs(AuthToken.class).token()).isNotBlank();
  }
}
