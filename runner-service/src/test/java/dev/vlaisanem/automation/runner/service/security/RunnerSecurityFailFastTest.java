package dev.vlaisanem.automation.runner.service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vlaisanem.automation.runner.RunnerServiceApplication;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;

/**
 * A misconfigured {@code PORTFOLIO} instance ({@link RunnerSecurityEnvironmentPostProcessor}'s
 * checks) must never open a listening socket, not merely fail after opening one. Runs the real
 * {@link RunnerServiceApplication} via {@link SpringApplication#run} (not a {@code @SpringBootTest}
 * slice) so a real embedded Tomcat would have to bind the port before the fail-fast check can be
 * proven.
 */
class RunnerSecurityFailFastTest {

  private static final String[] PROPERTIES_TO_CLEAR = {
    "server.port",
    "runner.deployment-profile",
    "RUNNER_SECURITY_GITHUB_CLIENT_ID",
    "RUNNER_SECURITY_GITHUB_CLIENT_SECRET",
    "RUNNER_SECURITY_ADMIN_GITHUB_ID",
    "server.servlet.session.cookie.secure"
  };

  @AfterEach
  void clearSystemProperties() {
    for (String property : PROPERTIES_TO_CLEAR) {
      System.clearProperty(property);
    }
  }

  /**
   * Case-insensitive: Spring's own enum conversion accepts {@code portfolio} lowercase too, so
   * comparing against the literal {@code "PORTFOLIO"} string alone would silently skip this check.
   */
  @ParameterizedTest
  @ValueSource(strings = {"PORTFOLIO", "portfolio"})
  void portfolioProfileWithoutCredentialsNeverOpensAListeningSocket(String profileValue)
      throws IOException {
    Map<String, String> extraProperties = new HashMap<>();
    extraProperties.put("runner.deployment-profile", profileValue);
    assertNeverOpensAListeningSocket(extraProperties, "RUNNER_SECURITY_GITHUB_CLIENT_ID");
  }

  /**
   * A non-Secure session cookie (e.g. a local Compose override left in place by mistake) must fail
   * closed exactly like missing GitHub credentials, not silently serve a production session.
   */
  @org.junit.jupiter.api.Test
  void portfolioProfileWithANonSecureCookieNeverOpensAListeningSocket() throws IOException {
    Map<String, String> extraProperties = new HashMap<>();
    extraProperties.put("runner.deployment-profile", "PORTFOLIO");
    extraProperties.put("RUNNER_SECURITY_GITHUB_CLIENT_ID", "test-client-id");
    extraProperties.put("RUNNER_SECURITY_GITHUB_CLIENT_SECRET", "test-client-secret");
    extraProperties.put("RUNNER_SECURITY_ADMIN_GITHUB_ID", "123456");
    extraProperties.put("server.servlet.session.cookie.secure", "false");
    assertNeverOpensAListeningSocket(extraProperties, "Secure session cookie");
  }

  private void assertNeverOpensAListeningSocket(
      Map<String, String> extraProperties, String expectedMessageFragment) throws IOException {
    int port;
    try (ServerSocket probe = new ServerSocket(0)) {
      port = probe.getLocalPort();
    }
    System.setProperty("server.port", String.valueOf(port));
    extraProperties.forEach(System::setProperty);

    assertThatThrownBy(() -> SpringApplication.run(RunnerServiceApplication.class))
        .hasStackTraceContaining(expectedMessageFragment);

    // If the embedded server had ever started listening, even briefly, binding the port here from
    // an independent socket would fail.
    try (ServerSocket confirmNeverBound = new ServerSocket(port)) {
      assertThat(confirmNeverBound.isBound()).isTrue();
    }
  }
}
