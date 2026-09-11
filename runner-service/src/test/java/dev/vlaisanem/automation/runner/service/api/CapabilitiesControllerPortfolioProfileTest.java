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
 * Proves PORTFOLIO wiring end to end via the real HTTP contract (a real deployment sets {@code
 * runner.deployment-profile=PORTFOLIO}). Separate from {@link CapabilitiesControllerTest} because
 * {@code @TestPropertySource} applies to the whole test class's Spring context.
 *
 * <p>Supplies the {@code RUNNER_SECURITY_*} properties and Secure cookie flag a real PORTFOLIO
 * deployment always has, since {@code RunnerSecurityEnvironmentPostProcessor} fails startup for
 * PORTFOLIO without them; OAuth2 client autoconfiguration is excluded since this slice never
 * imports the security wiring it would otherwise compete with.
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
