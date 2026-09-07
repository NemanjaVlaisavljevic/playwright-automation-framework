package dev.vlaisanem.automation.runner.service.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vlaisanem.automation.runner.service.config.RunAvailabilityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The portfolio deployment sets {@code runner.deployment-profile=PORTFOLIO} (see {@code
 * deploy/docker-compose.yml}) - proves the wiring end to end through the real HTTP contract, not
 * just {@code RunAvailabilityPolicy}/{@code CapabilitiesResponse} in isolation. A separate class
 * from {@link CapabilitiesControllerTest}, not a second {@code @Test} there, since
 * {@code @TestPropertySource} applies for the whole test class's Spring context.
 *
 * <p>Also supplies the three {@code RUNNER_SECURITY_*} properties and the Secure session cookie
 * flag a real {@code PORTFOLIO} deployment always has configured (see {@code
 * deploy/docker-compose.yml}/{@code deploy/runner-service/Dockerfile}) - {@code
 * RunnerSecurityEnvironmentPostProcessor} fails startup outright for {@code PORTFOLIO} without
 * them, in this narrow slice's own bootstrap exactly as it would for the real application. Once
 * those properties are present, Spring Boot's own {@code OAuth2ClientAutoConfiguration}/{@code
 * OAuth2ClientWebSecurityAutoConfiguration} try to build a competing default {@code
 * SecurityFilterChain} - harmless in the real application (this project's own {@code
 * SecurityConfig}/{@code @EnableWebSecurity} already satisfy their {@code HttpSecurity} dependency,
 * so they back off via {@code @ConditionalOnMissingBean}), but this narrow slice never imports that
 * wiring at all, since it tests only {@code CapabilitiesController}'s business logic - excluded
 * here for exactly that reason, the same {@code spring.autoconfigure.exclude} pattern already used
 * by {@code OpenApiContractTest}/{@code ServerBindingTest} for D2.3's DataSource/Flyway
 * autoconfiguration.
 */
@WebMvcTest(controllers = CapabilitiesController.class)
@Import(RunAvailabilityConfig.class)
@TestPropertySource(
    properties = {
      "runner.deployment-profile=PORTFOLIO",
      "RUNNER_SECURITY_GITHUB_CLIENT_ID=test-client-id",
      "RUNNER_SECURITY_GITHUB_CLIENT_SECRET=test-client-secret",
      "RUNNER_SECURITY_ADMIN_GITHUB_ID=123456",
      "server.servlet.session.cookie.secure=true",
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration,"
          + "org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration"
    })
class CapabilitiesControllerPortfolioProfileTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void getOmitsLocalEntirely() throws Exception {
    mockMvc
        .perform(get("/api/v1/capabilities"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.environments.length()").value(1))
        .andExpect(jsonPath("$.environments[0].name").value("PUBLIC"))
        .andExpect(jsonPath("$.environments[0].suites[6]").value("CUSTOM"));
  }
}
