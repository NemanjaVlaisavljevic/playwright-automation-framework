package dev.vlaisanem.automation.runner.service.security;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vlaisanem.automation.runner.service.api.RunController;
import dev.vlaisanem.automation.runner.service.api.RunExceptionHandler;
import dev.vlaisanem.automation.runner.service.artifacts.ArtifactRepository;
import dev.vlaisanem.automation.runner.service.config.JacksonConfig;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.orchestration.RunService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;

@WebMvcTest(controllers = {RunController.class, RunExceptionHandler.class})
@Import({
  SecurityConfig.class,
  JacksonConfig.class,
  PermissiveChainCsrfProtectionTest.MockMvcSecurityConfig.class
})
@TestPropertySource(properties = "runner.security.oauth2-enabled=false")
class PermissiveChainCsrfProtectionTest {

  private static final String RUN_ID = "11111111-1111-4111-8111-111111111111";
  private static final String CREATE_BODY = "{\"environment\":\"PUBLIC\",\"suite\":\"SMOKE\"}";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private RunService runService;
  @MockitoBean private ArtifactRepository artifactRepository;

  @Test
  void mutatingRequestWithoutCsrfIsForbidden() throws Exception {
    mockMvc
        .perform(post("/api/v1/runs").contentType("application/json").content(CREATE_BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  void mutatingRequestWithCsrfStillReachesThePermissiveController() throws Exception {
    when(runService.submit(Environment.PUBLIC, Suite.SMOKE, null))
        .thenReturn(
            Run.queued(
                RUN_ID, Environment.PUBLIC, Suite.SMOKE, Instant.parse("2026-09-10T00:00:00Z")));

    mockMvc
        .perform(
            post("/api/v1/runs").with(csrf()).contentType("application/json").content(CREATE_BODY))
        .andExpect(status().isAccepted());
  }

  @TestConfiguration
  static class MockMvcSecurityConfig {

    @Bean
    MockMvcBuilderCustomizer securityMockMvcBuilderCustomizer() {
      return (ConfigurableMockMvcBuilder<?> builder) ->
          builder.apply(
              org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                  .springSecurity());
    }
  }
}
