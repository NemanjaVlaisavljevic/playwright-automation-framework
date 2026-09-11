package dev.vlaisanem.automation.runner.service.security;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vlaisanem.automation.runner.service.api.CurrentUserController;
import dev.vlaisanem.automation.runner.service.api.RunController;
import dev.vlaisanem.automation.runner.service.api.RunExceptionHandler;
import dev.vlaisanem.automation.runner.service.artifacts.ArtifactRepository;
import dev.vlaisanem.automation.runner.service.config.JacksonConfig;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageController;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService.DiskUsageSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.exception.UnsupportedRunCombinationException;
import dev.vlaisanem.automation.runner.service.orchestration.RunService;
import dev.vlaisanem.automation.runner.service.retention.RetentionController;
import dev.vlaisanem.automation.runner.service.retention.RetentionReport;
import dev.vlaisanem.automation.runner.service.retention.RetentionService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Exercises the real {@link SecurityConfig#oauth2SecurityFilterChain} - the one place the access
 * matrix is proven end-to-end. Per-controller slice tests elsewhere never import {@code
 * SecurityConfig} and so enforce no authorization at all (a {@code @WebMvcTest} slice with no
 * {@code SecurityFilterChain} bean applies no security filtering, rather than falling back to a
 * default-deny chain).
 *
 * <p>Uses {@code @WithMockUser} to prove the authorization rule only (admin vs non-admin), never a
 * real GitHub round trip - see {@link GithubOAuth2UserServiceTest} for that mapping.
 */
@WebMvcTest(
    controllers = {
      RunController.class,
      RunExceptionHandler.class,
      CurrentUserController.class,
      RetentionController.class,
      DiskUsageController.class
    })
@Import({
  // SecurityConfig provides InMemoryRateLimiter/AbuseRateLimitFilter as @Bean methods, so
  // importing it alone is enough. RunnerProperties comes from RunnerServiceApplication's own
  // @EnableConfigurationProperties, already implicitly discovered by @WebMvcTest - an explicit
  // test bean here would conflict (NoUniqueBeanDefinitionException).
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
  @MockitoBean private ArtifactRepository artifactRepository;
  @MockitoBean private RetentionService retentionService;
  @MockitoBean private DiskUsageService diskUsageService;

  /**
   * A {@code @WebMvcTest} slice has no real {@code ClientRegistrationRepository} to resolve the
   * "github" registration from - a minimal fake is enough since this class only tests authorization
   * rules, never an actual OAuth2 redirect/callback.
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
     * {@code springSecurity()} is required for {@code @WithMockUser} to be honored under
     * {@code @WebMvcTest}'s MockMvc dispatch. {@link ForwardedHeaderFilter} only stands in for
     * MockMvc's {@code {baseUrl}} resolution - it does not exercise production's real trust
     * boundary (Tomcat's {@code RemoteIpValve}), which is proven separately by {@link
     * PermissiveChainHasNoAbuseRateLimitTest}/{@link OAuth2ChainAppliesAbuseRateLimitTest} and
     * {@code docs/RELEASE_EVIDENCE.md}.
     */
    @Bean
    MockMvcBuilderCustomizer securityMockMvcBuilderCustomizer() {
      return (ConfigurableMockMvcBuilder<?> builder) ->
          builder
              // Must run before Spring Security's own chain sees the request, so {baseUrl}
              // resolution sees the recovered X-Forwarded-* scheme/host.
              .addFilter(new ForwardedHeaderFilter())
              .apply(SecurityMockMvcConfigurers.springSecurity());
    }
  }

  private static final String CREATE_BODY = "{\"environment\":\"PUBLIC\",\"suite\":\"SMOKE\"}";

  /**
   * Supplies a valid CSRF token to isolate the authentication check - an anonymous POST with no
   * CSRF token is rejected earlier, by the CSRF filter (403), covered separately by {@link
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
   * Without trusting forwarded headers, {@code {baseUrl}} resolution would see the internal
   * plain-HTTP hop to this service, not the public {@code https://} origin - producing a {@code
   * redirect_uri} that never matches GitHub OAuth App registration. See {@code
   * FakeClientRegistrationConfig}'s Javadoc for why {@link ForwardedHeaderFilter} stands in for
   * production's real Tomcat {@code RemoteIpValve} mechanism here.
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
   * A route under {@code /api/v1/auth} that was never explicitly allowlisted must still be denied -
   * confirms there is no single grouped {@code /api/v1/auth/**} permission covering the namespace.
   */
  @Test
  void anUnlistedRouteUnderTheAuthNamespaceIsDeniedByDefault() throws Exception {
    mockMvc.perform(get("/api/v1/auth/not-a-real-route")).andExpect(status().isUnauthorized());
  }

  /**
   * Authorization and business validation are independent layers - an authenticated admin gets the
   * same 400 an anonymous caller would for an unsupported combination; {@code ROLE_ADMIN} does not
   * bypass {@code RunAvailabilityPolicy}/{@code RunRequestValidator}.
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

  /**
   * A real {@link DefaultOAuth2User}, not {@code @WithMockUser} - {@link AbuseRateLimitFilter}'s
   * admin-keyed limits read the numeric GitHub {@code "id"} off the principal, which
   * {@code @WithMockUser}'s principal doesn't carry. Without it, a missing key makes the filter let
   * the request through rather than block it, so admin-keyed rate-limit tests would silently no-op.
   */
  private static Authentication realAdminAuthentication() {
    DefaultOAuth2User oauth2User =
        new DefaultOAuth2User(
            Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")),
            Map.of("id", 999, "login", "octocat"),
            "id");
    return new TestingAuthenticationToken(oauth2User, null, oauth2User.getAuthorities());
  }

  /**
   * Create-run is rate-limited per admin (numeric GitHub id), not left uncapped just because the
   * caller is authorized. Real default is 3/min - the 4th call in the window is rejected.
   */
  @Test
  void createRunIsRateLimitedPerAdminAfterTheConfiguredThreshold() throws Exception {
    Run queued =
        Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, Instant.parse("2026-09-07T00:00:00Z"));
    when(runService.submit(Environment.PUBLIC, Suite.SMOKE, null)).thenReturn(queued);
    Authentication admin = realAdminAuthentication();

    for (int i = 0; i < 3; i++) {
      mockMvc
          .perform(
              post("/api/v1/runs")
                  .with(csrf())
                  .with(authentication(admin))
                  .contentType("application/json")
                  .content(CREATE_BODY))
          .andExpect(status().isAccepted());
    }

    mockMvc
        .perform(
            post("/api/v1/runs")
                .with(csrf())
                .with(authentication(admin))
                .contentType("application/json")
                .content(CREATE_BODY))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.status").value(429));
  }

  /**
   * Public reads (the largest anonymous surface) are rate-limited per client IP. Real default is
   * 120/min - the 121st call is rejected. Uses a distinct reserved-range test IP, not the shared
   * {@code 127.0.0.1} every other test in this class uses, so limit state can't collide across
   * tests.
   */
  @Test
  void publicReadsAreRateLimitedPerClientIpAfterTheConfiguredThreshold() throws Exception {
    for (int i = 0; i < 120; i++) {
      mockMvc
          .perform(get("/api/v1/runs").with(remoteAddr("203.0.113.7")))
          .andExpect(status().isOk());
    }

    mockMvc
        .perform(get("/api/v1/runs").with(remoteAddr("203.0.113.7")))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.status").value(429));
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor remoteAddr(
      String address) {
    return request -> {
      request.setRemoteAddr(address);
      return request;
    };
  }

  /**
   * An oversized body is rejected by {@code Content-Length} alone, before deserialization/CSRF/
   * authorization - a request with no CSRF token still gets 413, not 403, proving this filter runs
   * first. Real default is 16384 bytes.
   */
  @Test
  void anOversizedRequestBodyIsRejectedWith413BeforeAnythingElse() throws Exception {
    String oversizedBody = "x".repeat(16385);

    mockMvc
        .perform(post("/api/v1/runs").contentType("application/json").content(oversizedBody))
        .andExpect(status().isContentTooLarge())
        .andExpect(jsonPath("$.status").value(413));
  }

  /**
   * "CORS deliberately unsupported" (see {@link SecurityConfig}'s class Javadoc) is a proven
   * decision, not merely an absent one - a cross-origin preflight gets no {@code
   * Access-Control-Allow-Origin} header, so a browser blocks the actual follow-up request.
   */
  @Test
  void aCrossOriginPreflightGetsNoAccessControlAllowOriginHeader() throws Exception {
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options(
                    "/api/v1/runs")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  /**
   * Same proof for a non-preflight cross-origin request against a public route - confirms the
   * missing header isn't just an artifact of the mutating-endpoint/authorization path above.
   */
  @Test
  void aCrossOriginGetRequestGetsNoAccessControlAllowOriginHeaderEither() throws Exception {
    mockMvc
        .perform(get("/api/v1/runs").header("Origin", "https://evil.example"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  /**
   * A mismatched CSRF cookie/header must be rejected like a missing one - {@link
   * #authenticatedAdminCreateWithoutCsrfIsForbidden} only proves the missing-token case; this
   * proves the repository actually compares values, not just checks presence.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void authenticatedAdminCreateWithAMismatchedCsrfTokenIsForbidden() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/runs")
                .cookie(new Cookie("XSRF-TOKEN", "cookie-value-the-attacker-cannot-read"))
                .header("X-XSRF-TOKEN", "a-different-guessed-value")
                .contentType("application/json")
                .content(CREATE_BODY))
        .andExpect(status().isForbidden());
  }

  /**
   * An invalidated session (logout, or eventual idle-timeout eviction - {@code
   * server.servlet.session.timeout: 4h} is an idle timeout, not an absolute lifetime) must be
   * treated as fully anonymous on its next use, never a lingering admin session. {@link
   * MockHttpSession#invalidate()} only simulates the post-invalidation state, not real wall-clock
   * idle time elapsing, so this doesn't test the 4h clock itself. Manipulates a real {@link
   * HttpSessionSecurityContextRepository}-backed session so it proves the actual session-backed
   * path, not {@code @WithMockUser}'s separate mechanism.
   */
  @Test
  void anInvalidatedSessionIsTreatedAsAnonymousNeverAsLingeringAdminOrAServerError()
      throws Exception {
    MockHttpSession session = new MockHttpSession();
    SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
    securityContext.setAuthentication(realAdminAuthentication());
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);

    // Sanity check: confirms the session really is an authenticated admin, so the assertions below
    // don't trivially pass for the wrong reason.
    mockMvc
        .perform(get("/api/v1/auth/me").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(true))
        .andExpect(jsonPath("$.canManageRuns").value(true));

    session.invalidate();

    mockMvc
        .perform(get("/api/v1/auth/me").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.authenticated").value(false))
        .andExpect(jsonPath("$.canManageRuns").value(false));

    mockMvc
        .perform(post("/api/v1/runs/run-1/cancel").with(csrf()).session(session))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
  }

  /**
   * {@code MUTATION} is not a value of the {@link Suite} enum at all (see its own Javadoc), so this
   * is structurally unreachable, not merely policy-rejected - proven by construction, not just by
   * reading the enum.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void aSuiteValueOutsideTheAllowlistedEnumIsRejectedWith400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/runs")
                .with(csrf())
                .contentType("application/json")
                .content("{\"environment\":\"PUBLIC\",\"suite\":\"MUTATION\"}"))
        .andExpect(status().isBadRequest());
  }

  private static final String FIXTURE_CREATE_BODY =
      "{\"environment\":\"PUBLIC\",\"suite\":\"FIXTURE\"}";

  /**
   * {@code FIXTURE} (the deliberately-always-fails drill-down fixture) is a legitimate, allowlisted
   * {@link Suite} value, but launching it is still an authenticated-admin-only mutation like any
   * other - never a client-choosable escape hatch reachable anonymously.
   */
  @Test
  void anonymousFixtureLaunchIsRejectedWithAProblemDetail401() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/runs")
                .with(csrf())
                .contentType("application/json")
                .content(FIXTURE_CREATE_BODY))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  @WithMockUser
  void authenticatedNonAdminFixtureLaunchIsForbidden() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/runs")
                .with(csrf())
                .contentType("application/json")
                .content(FIXTURE_CREATE_BODY))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void authenticatedAdminFixtureLaunchSucceeds() throws Exception {
    Run queued =
        Run.queued(
            "run-1", Environment.PUBLIC, Suite.FIXTURE, Instant.parse("2026-09-07T00:00:00Z"));
    when(runService.submit(Environment.PUBLIC, Suite.FIXTURE, null)).thenReturn(queued);

    mockMvc
        .perform(
            post("/api/v1/runs")
                .with(csrf())
                .contentType("application/json")
                .content(FIXTURE_CREATE_BODY))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.runId").value("run-1"));
  }

  // RetentionController: same full access-matrix treatment (401/403/200/CSRF/429) as every other
  // admin-only mutation surface above.

  private static final RetentionReport A_REPORT =
      new RetentionReport(false, 0, 0, 0, 0, 0, 0, 0, false);

  @Test
  void anonymousRetentionPreviewIsRejectedWithAProblemDetail401() throws Exception {
    mockMvc
        .perform(get("/api/v1/retention/preview"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  void anonymousRetentionRunIsRejectedWithAProblemDetail401() throws Exception {
    mockMvc
        .perform(post("/api/v1/retention/run").with(csrf()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  @WithMockUser
  void authenticatedNonAdminRetentionPreviewIsForbidden() throws Exception {
    mockMvc
        .perform(get("/api/v1/retention/preview"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403));
  }

  @Test
  @WithMockUser
  void authenticatedNonAdminRetentionRunIsForbidden() throws Exception {
    mockMvc
        .perform(post("/api/v1/retention/run").with(csrf()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void authenticatedAdminRetentionPreviewSucceeds() throws Exception {
    when(retentionService.sweep(true)).thenReturn(A_REPORT);

    mockMvc.perform(get("/api/v1/retention/preview")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void authenticatedAdminRetentionRunWithoutCsrfIsForbidden() throws Exception {
    mockMvc.perform(post("/api/v1/retention/run")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void authenticatedAdminRetentionRunWithCsrfSucceeds() throws Exception {
    when(retentionService.sweep(false)).thenReturn(A_REPORT);

    mockMvc.perform(post("/api/v1/retention/run").with(csrf())).andExpect(status().isOk());
  }

  /**
   * A real sweep does real DB/filesystem work, so this admin-only route is rate-limited per admin
   * like create-run/cancel-run above. Real default is 10/hour - the 11th call is rejected.
   */
  @Test
  void retentionRunIsRateLimitedPerAdminAfterTheConfiguredThreshold() throws Exception {
    when(retentionService.sweep(false)).thenReturn(A_REPORT);
    Authentication admin = realAdminAuthentication();

    for (int i = 0; i < 10; i++) {
      mockMvc
          .perform(post("/api/v1/retention/run").with(csrf()).with(authentication(admin)))
          .andExpect(status().isOk());
    }

    mockMvc
        .perform(post("/api/v1/retention/run").with(csrf()).with(authentication(admin)))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.status").value(429));
  }

  /**
   * Same threshold, tracked as its own independent counter from {@code POST .../run} above - the
   * cheap, read-only dry-run preview must not share (or be starved by) the expensive real sweep's
   * budget, and vice versa.
   */
  @Test
  void retentionPreviewIsRateLimitedPerAdminAfterTheConfiguredThresholdIndependentlyOfRun()
      throws Exception {
    when(retentionService.sweep(true)).thenReturn(A_REPORT);
    Authentication admin = realAdminAuthentication();

    for (int i = 0; i < 10; i++) {
      mockMvc
          .perform(get("/api/v1/retention/preview").with(authentication(admin)))
          .andExpect(status().isOk());
    }

    mockMvc
        .perform(get("/api/v1/retention/preview").with(authentication(admin)))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.status").value(429));
  }

  // DiskUsageController: same full access-matrix treatment (401/403/200/429) as every other
  // admin-only diagnostic surface above.

  private static final DiskUsageSnapshot A_SNAPSHOT =
      new DiskUsageSnapshot(1_000_000_000L, 1_048_576L, 314_572_800L, Instant.now());

  @Test
  void anonymousDiskUsageIsRejectedWithAProblemDetail401() throws Exception {
    mockMvc
        .perform(get("/api/v1/disk/usage"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401));
  }

  @Test
  @WithMockUser
  void authenticatedNonAdminDiskUsageIsForbidden() throws Exception {
    mockMvc
        .perform(get("/api/v1/disk/usage"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.status").value(403));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void authenticatedAdminDiskUsageSucceeds() throws Exception {
    when(diskUsageService.snapshot()).thenReturn(A_SNAPSHOT);

    mockMvc.perform(get("/api/v1/disk/usage")).andExpect(status().isOk());
  }

  /**
   * A filesystem-tree walk plus a live Postgres size query is real work, so this admin-only route
   * is rate-limited per admin like every other admin-only surface above. Real default is 10/hour -
   * the 11th call is rejected.
   */
  @Test
  void diskUsageIsRateLimitedPerAdminAfterTheConfiguredThreshold() throws Exception {
    when(diskUsageService.snapshot()).thenReturn(A_SNAPSHOT);
    Authentication admin = realAdminAuthentication();

    for (int i = 0; i < 10; i++) {
      mockMvc
          .perform(get("/api/v1/disk/usage").with(authentication(admin)))
          .andExpect(status().isOk());
    }

    mockMvc
        .perform(get("/api/v1/disk/usage").with(authentication(admin)))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().exists("Retry-After"))
        .andExpect(jsonPath("$.status").value(429));
  }
}
