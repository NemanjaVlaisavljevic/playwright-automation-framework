package dev.vlaisanem.automation.runner.service.security;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vlaisanem.automation.runner.service.api.CurrentUserController;
import dev.vlaisanem.automation.runner.service.api.RunController;
import dev.vlaisanem.automation.runner.service.api.RunExceptionHandler;
import dev.vlaisanem.automation.runner.service.config.JacksonConfig;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.exception.UnsupportedRunCombinationException;
import dev.vlaisanem.automation.runner.service.orchestration.RunService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Exercises the real {@link SecurityConfig#oauth2SecurityFilterChain} - not the narrower per-
 * controller slice tests elsewhere, which never import {@link SecurityConfig} at all and so never
 * enforce authorization (confirmed empirically: those tests still pass unmodified after adding
 * {@code spring-boot-starter-security}, since a {@code @WebMvcTest} slice with no {@code
 * SecurityFilterChain} bean visible to it applies no security filtering whatsoever, rather than
 * falling back to Spring Boot's own default-deny chain). This is the one place the actual access
 * matrix is proven end-to-end.
 *
 * <p>Uses {@code @WithMockUser} to simulate the authenticated-admin/authenticated-non-admin cases
 * directly - this only ever needs to prove the authorization <em>rule</em> (does a caller with/
 * without {@code ROLE_ADMIN} get through), never a real GitHub OAuth round trip; see {@link
 * GithubOAuth2UserServiceTest} for how a real GitHub response maps to that role.
 */
@WebMvcTest(
    controllers = {RunController.class, RunExceptionHandler.class, CurrentUserController.class})
@Import({
  SecurityConfig.class,
  GithubOAuth2UserService.class,
  ProblemDetailAuthenticationEntryPoint.class,
  ProblemDetailAccessDeniedHandler.class,
  JacksonConfig.class,
  SecurityAccessMatrixTest.FakeClientRegistrationConfig.class
})
@org.springframework.test.context.TestPropertySource(
    properties = {"runner.security.oauth2-enabled=true", "runner.security.admin-github-id=999"})
class SecurityAccessMatrixTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private RunService runService;

  /**
   * A {@code @WebMvcTest} slice does not retain {@code OAuth2ClientAutoConfiguration} (unlike a
   * full application context), so {@code oauth2Login()} has no real {@code
   * ClientRegistrationRepository} to resolve the "github" registration from. This test never
   * exercises an actual OAuth2 redirect/callback - only the authorization rules around it - so a
   * minimal fake registration is enough to satisfy the filter chain's own wiring requirements.
   */
  @TestConfiguration
  static class FakeClientRegistrationConfig {
    @Bean
    ClientRegistrationRepository clientRegistrationRepository() {
      ClientRegistration github =
          ClientRegistration.withRegistrationId("github")
              .clientId("test-client-id")
              .clientSecret("test-client-secret")
              .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
              .redirectUri("{baseUrl}/api/v1/auth/oauth2/callback/{registrationId}")
              .authorizationUri("https://github.invalid/login/oauth/authorize")
              .tokenUri("https://github.invalid/login/oauth/access_token")
              .userInfoUri("https://github.invalid/user")
              .userNameAttributeName("id")
              .build();
      return new InMemoryClientRegistrationRepository(github);
    }

    /**
     * Without {@code springSecurity()}, {@code @WithMockUser}'s established {@code SecurityContext}
     * is not honored by the real filter chain during a {@code @WebMvcTest}-composed {@code MockMvc}
     * dispatch - confirmed empirically (every {@code @WithMockUser} test resolved as anonymous/401
     * until this was added). {@link ForwardedHeaderFilter} lets {@link
     * #oauth2LoginRedirectUsesTheForwardedHttpsSchemeAndHost} simulate the real production topology
     * (Caddy terminates TLS and sets {@code X-Forwarded-Proto}/{@code X-Forwarded-Host},
     * `SERVER_FORWARD_HEADERS_STRATEGY=framework` in `deploy/runner-service/Dockerfile` makes
     * Spring trust them) - harmless for every other test here, which never sends those headers.
     */
    @Bean
    MockMvcBuilderCustomizer securityMockMvcBuilderCustomizer() {
      return (ConfigurableMockMvcBuilder<?> builder) ->
          builder
              // ForwardedHeaderFilter must run before Spring Security's own filter chain sees the
              // request - registered first so it wraps the request (recovering the real
              // X-Forwarded-* scheme/host) before security's {baseUrl} resolution ever runs.
              .addFilter(new ForwardedHeaderFilter())
              .apply(SecurityMockMvcConfigurers.springSecurity());
    }
  }

  private static final String CREATE_BODY = "{\"environment\":\"PUBLIC\",\"suite\":\"SMOKE\"}";

  /**
   * A valid CSRF token is deliberately supplied so this isolates the authentication check itself -
   * an anonymous POST with no CSRF token at all is rejected earlier, by the CSRF filter (403), a
   * different and equally legitimate rejection reason covered separately by {@link
   * #authenticatedAdminCreateWithoutCsrfIsForbidden}.
   */
  @Test
  void anonymousCreateIsRejectedWithAProblemDetail401() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/runs").with(csrf()).contentType("application/json").content(CREATE_BODY))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  void anonymousCancelIsRejectedWithAProblemDetail401() throws Exception {
    mockMvc
        .perform(post("/api/v1/runs/run-1/cancel").with(csrf()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  @WithMockUser
  void authenticatedNonAdminCreateIsForbidden() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/runs").with(csrf()).contentType("application/json").content(CREATE_BODY))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void authenticatedAdminCreateWithoutCsrfIsForbidden() throws Exception {
    mockMvc
        .perform(post("/api/v1/runs").contentType("application/json").content(CREATE_BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void authenticatedAdminCreateWithCsrfSucceeds() throws Exception {
    Run queued =
        Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, Instant.parse("2026-09-07T00:00:00Z"));
    when(runService.submit(Environment.PUBLIC, Suite.SMOKE, null)).thenReturn(queued);

    mockMvc
        .perform(
            post("/api/v1/runs").with(csrf()).contentType("application/json").content(CREATE_BODY))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.runId").value("run-1"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void authenticatedAdminCancelWithCsrfSucceeds() throws Exception {
    Run cancelled =
        Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, Instant.parse("2026-09-07T00:00:00Z"));
    when(runService.cancel("run-1")).thenReturn(cancelled);

    mockMvc.perform(post("/api/v1/runs/run-1/cancel").with(csrf())).andExpect(status().isOk());
  }

  @Test
  void anonymousGetRunsIsPermitted() throws Exception {
    mockMvc.perform(get("/api/v1/runs")).andExpect(status().isOk());
  }

  @Test
  void anonymousGetCurrentUserIsPermittedAndReportsAnonymous() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticationRequired").value(true))
        .andExpect(jsonPath("$.canManageRuns").value(false))
        .andExpect(jsonPath("$.authenticated").value(false));
  }

  /**
   * Regression test for the review finding: without {@code
   * SERVER_FORWARD_HEADERS_STRATEGY=framework} (see `deploy/runner-service/Dockerfile`), Spring
   * Security's {@code {baseUrl}} resolution would see the internal plain-HTTP hop between Caddy and
   * this service, not the real public {@code https://} origin the browser actually used - producing
   * a {@code redirect_uri} that never matches the one registered with the GitHub OAuth App. {@link
   * ForwardedHeaderFilter} (registered on this test's own {@code MockMvc}, mirroring that same
   * Dockerfile setting) makes the simulated {@code X-Forwarded-Proto}/{@code X-Forwarded-Host}
   * headers - exactly what Caddy's {@code reverse_proxy} sets by default - authoritative for that
   * resolution.
   */
  @Test
  void oauth2LoginRedirectUsesTheForwardedHttpsSchemeAndHost() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/auth/oauth2/authorization/github")
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "example.com"))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            header().string("Location", startsWith("https://github.invalid/login/oauth/authorize")))
        .andExpect(
            header()
                .string(
                    "Location",
                    org.hamcrest.Matchers.containsString(
                        "redirect_uri=https://example.com/api/v1/auth/oauth2/callback/github")));
  }

  /**
   * Regression proof for the default-deny design: a route under the same {@code /api/v1/auth}
   * namespace that was never explicitly allowlisted must still be denied, confirming there is no
   * single grouped {@code /api/v1/auth/**} permission covering the whole namespace.
   */
  @Test
  void anUnlistedRouteUnderTheAuthNamespaceIsDeniedByDefault() throws Exception {
    mockMvc.perform(get("/api/v1/auth/not-a-real-route")).andExpect(status().isUnauthorized());
  }

  /**
   * Proves authorization and existing business validation are two independent, composable layers:
   * an authenticated admin still gets the same 400 an anonymous {@code PUBLIC} caller would have
   * gotten for an unsupported combination - {@code ROLE_ADMIN} does not bypass {@code
   * RunAvailabilityPolicy}/{@code RunRequestValidator}.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void adminIsStillSubjectToExistingRunCombinationValidation() throws Exception {
    when(runService.submit(eq(Environment.LOCAL), eq(Suite.SMOKE), isNull()))
        .thenThrow(new UnsupportedRunCombinationException(Environment.LOCAL, Suite.SMOKE));

    mockMvc
        .perform(
            post("/api/v1/runs")
                .with(csrf())
                .contentType("application/json")
                .content("{\"environment\":\"LOCAL\",\"suite\":\"SMOKE\"}"))
        .andExpect(status().isBadRequest());
  }
}
