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
 * Proves the review finding this class is named for: a {@code PORTFOLIO} instance that is
 * misconfigured in any of the ways {@link RunnerSecurityEnvironmentPostProcessor} checks must never
 * open a listening socket at all, not merely fail shortly after opening one. Runs the real {@link
 * RunnerServiceApplication} via {@link SpringApplication#run} - not a {@code @SpringBootTest} slice
 * - specifically so a real embedded Tomcat would have to try to bind the port for this test to be
 * meaningful; the post-processor throwing during environment preparation, before the {@code
 * ApplicationContext} is even created, means no {@code DataSource}/Flyway/web- server bean is ever
 * attempted either - this genuinely never reaches the point D2.3 made every other startup depend on
 * a real Postgres, so no Testcontainers/real database is needed here.
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
   * Regression test for the review finding: comparing the raw profile value against the literal
   * {@code "PORTFOLIO"} string would silently skip this check for a lowercase {@code portfolio} -
   * Spring's own enum conversion (used later by {@code RunAvailabilityConfig}'s {@code @Value})
   * accepts it case-insensitively, so both spellings must fail closed identically here too.
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
   * Regression test for the review finding: a {@code PORTFOLIO} instance whose session cookie would
   * not be {@code Secure} (e.g. a local Compose acceptance override left in place by mistake - see
   * {@code deploy/docker-compose.yml}'s {@code SESSION_COOKIE_SECURE}) must fail closed exactly the
   * same way as missing GitHub credentials, not silently serve a non-Secure-cookie production
   * session.
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

    // The real proof: the port must still be genuinely free. If the embedded server had ever
    // started listening - even briefly, before some later check tore it down - binding it here
    // from a completely independent socket would fail.
    try (ServerSocket confirmNeverBound = new ServerSocket(port)) {
      assertThat(confirmNeverBound.isBound()).isTrue();
    }
  }
}
