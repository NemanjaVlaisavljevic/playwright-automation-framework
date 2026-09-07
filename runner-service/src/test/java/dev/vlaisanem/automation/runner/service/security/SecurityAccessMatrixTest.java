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
import dev.vlaisanem.automation.runner.service.config.JacksonConfig;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.exception.UnsupportedRunCombinationException;
import dev.vlaisanem.automation.runner.service.orchestration.RunService;
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
  // D3.3 - SecurityConfig itself now provides InMemoryRateLimiter/AbuseRateLimitFilter as
  // explicit @Bean methods (deliberately not bare @Component classes - see
  // AbuseRateLimitFilter's own Javadoc), so importing SecurityConfig alone is enough; no separate
  // import needed for either. AbuseRateLimitFilter needs a real RunnerProperties bean too - no
  // explicit test bean is supplied for it here because @WebMvcTest already implicitly discovers
  // RunnerServiceApplication as this slice's @SpringBootConfiguration source, and that class's own
  // @EnableConfigurationProperties(RunnerProperties.class) already binds the real
  // application.yml defaults - a second, explicit bean here would conflict
  // (NoUniqueBeanDefinitionException), confirmed empirically.
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
     * #oauth2LoginRedirectUsesTheForwardedHttpsSchemeAndHost} simulate the effect of trusting
     * forwarded headers (Caddy terminates TLS and sets {@code X-Forwarded-Proto}/{@code
     * X-Forwarded-Host}) without actually exercising production's real mechanism - {@code
     * deploy/runner-service/Dockerfile} sets {@code SERVER_FORWARD_HEADERS_STRATEGY=native} (D3.3),
     * which activates Tomcat's connector-level {@code RemoteIpValve}, not this servlet {@link
     * ForwardedHeaderFilter}; {@code MockMvc} dispatches directly to {@code DispatcherServlet} and
     * never boots a real embedded Tomcat, so it cannot exercise a {@code Valve} at all - this
     * filter is a same-effect stand-in for the {@code {baseUrl}}-resolution behavior only, not a
     * proof that {@code RemoteIpValve}'s own trust boundary (its {@code internal-proxies} check in
     * particular) behaves the same way - {@link PermissiveChainHasNoAbuseRateLimitTest}/{@link
     * OAuth2ChainAppliesAbuseRateLimitTest} prove the real, embedded-Tomcat chain-scoping fix, and
     * {@code docs/RELEASE_EVIDENCE.md}'s D3.3 section carries the real Compose-stack proof of the
     * {@code RemoteIpValve} trust boundary itself - {@code MockMvc} cannot exercise either.
     * Harmless for every other test here, which never sends those headers.
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
   * Regression test for the review finding: without trusting forwarded headers at all, Spring
   * Security's {@code {baseUrl}} resolution would see the internal plain-HTTP hop between Caddy and
   * this service, not the real public {@code https://} origin the browser actually used - producing
   * a {@code redirect_uri} that never matches the one registered with the GitHub OAuth App. {@link
   * ForwardedHeaderFilter} (registered on this test's own {@code MockMvc}) makes the simulated
   * {@code X-Forwarded-Proto}/{@code X-Forwarded-Host} headers - exactly what Caddy's {@code
   * reverse_proxy} sets by default - authoritative for that resolution here; production itself uses
   * Tomcat's {@code RemoteIpValve} instead (D3.3, {@code SERVER_FORWARD_HEADERS_STRATEGY=native} -
   * see {@code deploy/runner-service/Dockerfile}), which this {@code MockMvc}-based test cannot
   * exercise directly (see {@code FakeClientRegistrationConfig}'s own note on that) - the {@code
   * {baseUrl}} resolution behavior this test proves is the same either way, just reached through a
   * different real mechanism.
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

  /**
   * A real {@link DefaultOAuth2User}, not {@code @WithMockUser} - {@link AbuseRateLimitFilter}'s
   * admin-keyed surfaces read the numeric GitHub {@code "id"} attribute directly off the principal,
   * which a plain {@code @WithMockUser}-established {@code UserDetails} principal does not carry at
   * all (confirmed empirically: with {@code @WithMockUser} alone, every admin-keyed rate limit
   * check silently no-ops, since the filter treats a missing key as "cannot rate-limit this, let it
   * through" rather than blocking a request it cannot identify).
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
   * Regression test for the D3.3 review finding: create-run must be rate-limited per admin (numeric
   * GitHub id), not left uncapped just because the caller is already authorized. Real {@code
   * application.yml} default is 3/min - the 4th call in the same window is rejected.
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
   * Regression test for the D3.3 review finding: the largest anonymous surface (public reads) must
   * itself be rate-limited per client IP, not left uncapped just because it needs no login at all.
   * Real {@code application.yml} default is 120/min - the 121st call in the same window is
   * rejected. Uses a distinct, reserved-range test IP (not the shared {@code 127.0.0.1} default
   * every other test in this class uses) so this test's own limit state can never collide with
   * theirs regardless of test execution order.
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
   * Regression test for D3.3: an oversized body is rejected by {@code Content-Length} alone, before
   * any deserialization/CSRF/authorization is even attempted - a request with no CSRF token at all
   * still gets {@code 413}, not the {@code 403} it would otherwise get for missing CSRF, proving
   * this filter runs ahead of all of that. Real {@code application.yml} default is 16384 bytes.
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
   * Regression test for D3.3: "CORS deliberately unsupported" (see {@link SecurityConfig}'s own
   * class Javadoc) must be a proven decision, not merely an absent one - a cross-origin-shaped
   * preflight against a mutating endpoint gets no {@code Access-Control-Allow-Origin} header at all
   * (Spring Security's default behavior once {@code .cors(...)} is never enabled), so a browser
   * blocks the actual follow-up request regardless of what this response otherwise says.
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
   * Same proof for an ordinary (non-preflight) cross-origin-shaped request against a public,
   * anonymously-readable route - confirms the missing header isn't merely an artifact of the
   * mutating-endpoint/authorization path above.
   */
  @Test
  void aCrossOriginGetRequestGetsNoAccessControlAllowOriginHeaderEither() throws Exception {
    mockMvc
        .perform(get("/api/v1/runs").header("Origin", "https://evil.example"))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
  }

  /**
   * D3.4 regression test: a stale/forged CSRF token (a cookie and header that plainly don't agree)
   * must be rejected exactly like a missing one - {@link
   * #authenticatedAdminCreateWithoutCsrfIsForbidden} only proves the missing-token case; this one
   * proves the repository actually compares values rather than merely checking presence.
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
   * D3.4 regression test: a session that has been invalidated - by an explicit logout, or by the
   * servlet container's own eventual idle-timeout eviction ({@code server.servlet.session.timeout:
   * 4h} - note this is an <em>idle</em> timeout per the Servlet {@code HttpSession} contract, not
   * an absolute session lifetime: an actively-used session is never force-expired at the 4h mark
   * just because 4h have passed since login) - must be treated as fully anonymous on its very next
   * use, never as a lingering admin session and never as a server error. This test proves only the
   * <em>post-invalidation</em> half of that: {@link MockHttpSession#invalidate()} simulates the
   * state a session is in immediately after invalidation happens, by whichever mechanism - it does
   * not, and cannot, simulate real wall- clock idle time actually elapsing (`MockMvc` has no notion
   * of that at all), so it is not a test of the 4h idle-timeout clock itself. Manipulates a real
   * {@link HttpSessionSecurityContextRepository}-backed session directly (the same attribute key
   * Spring Security's own session persistence uses) so this proves the actual session-backed
   * authentication path, not {@code @WithMockUser}'s separate test-only mechanism.
   */
  @Test
  void anInvalidatedSessionIsTreatedAsAnonymousNeverAsLingeringAdminOrAServerError()
      throws Exception {
    MockHttpSession session = new MockHttpSession();
    SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
    securityContext.setAuthentication(realAdminAuthentication());
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);

    // Sanity check: while still valid, this session really is an authenticated admin - otherwise
    // the assertions below would trivially pass for the wrong reason.
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
   * D3.4 regression test: {@code MUTATION} is not even a value of the {@link Suite} enum (see its
   * own Javadoc - the REST API only ever accepts the fixed, allowlisted suites {@code
   * SuiteCommandFactory} maps to a static Gradle task), so it is structurally unreachable, not
   * merely policy-rejected - proves that by construction rather than asserting it from reading the
   * enum alone.
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
   * D3.4 regression test: {@code FIXTURE} (the deliberately-always-fails step/failure/artifact
   * drill-down fixture) is a legitimate, allowlisted {@link Suite} value - but launching it is
   * still an authenticated-admin-only mutation like any other, never a client-choosable escape
   * hatch reachable anonymously. Same authorization rule as {@code SMOKE}/{@code REGRESSION} above,
   * asserted explicitly for this specific suite value rather than assumed from the fact that
   * authorization is keyed on HTTP method+path, not request body content.
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
}
