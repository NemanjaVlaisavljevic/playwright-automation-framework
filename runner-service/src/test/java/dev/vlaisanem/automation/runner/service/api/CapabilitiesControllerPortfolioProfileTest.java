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
 */
@WebMvcTest(controllers = CapabilitiesController.class)
@Import(RunAvailabilityConfig.class)
@TestPropertySource(properties = "runner.deployment-profile=PORTFOLIO")
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
