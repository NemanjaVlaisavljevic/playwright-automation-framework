package dev.vlaisanem.automation.runner.service.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.config.RunnerSecurityProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CorsConfigurer;
import org.springframework.security.config.annotation.web.configurers.LogoutConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Two mutually exclusive {@link SecurityFilterChain} beans, selected by whether GitHub OAuth2
 * credentials were configured at startup - never both active at once.
 *
 * <p>CORS is deliberately unsupported on both chains: no {@code CorsConfigurationSource} bean
 * exists anywhere in this service, and {@code .cors(CorsConfigurer::disable)} below makes that a
 * stated, tested decision - this API is same-origin-only by design (Caddy in production, Vite's dev
 * proxy locally), so no cross-origin browser access is ever legitimate.
 *
 * <p>The OAuth2 chain's {@code request.getRemoteAddr()} trust boundary: {@code
 * deploy/runner-service/Dockerfile} sets {@code SERVER_FORWARD_HEADERS_STRATEGY=native}, which
 * activates Tomcat's {@code RemoteIpValve} instead of the plain {@code ForwardedHeaderFilter} the
 * {@code framework} strategy would - the valve only honors {@code X-Forwarded-For}/{@code
 * -Proto}/{@code -Host} when the direct TCP peer is a trusted (private/Docker-internal) range,
 * which in this topology can only be Caddy. A plain {@code ForwardedHeaderFilter} has no such
 * concept and would parse the header unconditionally regardless of who sent it.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  /**
   * Unconditional (unlike the OAuth2 chain itself) - {@code CsrfController} needs a real repository
   * to explicitly prime the {@code XSRF-TOKEN} cookie regardless of which chain is active, and
   * constructing this instance has no dependency on whether OAuth2 is enabled.
   */
  @Bean
  public CsrfTokenRepository csrfTokenRepository() {
    return CookieCsrfTokenRepository.withHttpOnlyFalse();
  }

  /**
   * Unconditional, like {@link #csrfTokenRepository()} - stateless and harmless to construct
   * regardless of which chain is active, and only ever actually consulted by {@link
   * #abuseRateLimitFilter}, which is itself only wired into the OAuth2 chain.
   */
  @Bean
  public InMemoryRateLimiter inMemoryRateLimiter() {
    return new InMemoryRateLimiter();
  }

  /**
   * An explicit {@code @Bean}, not a bare {@code @Component}: {@code @WebMvcTest} auto-detects and
   * registers any {@code Filter} bean in any slice, breaking unrelated tests. Spring Boot also
   * auto-registers any {@code Filter} bean as a bare, unconditional servlet filter in a real
   * running application (including the permissive/local chain, where it must not apply at all),
   * independent of it also being added via {@code .addFilterAfter(...)} below. {@link
   * #abuseRateLimitFilterRegistration} disables that automatic registration, so this filter only
   * ever runs at the position {@code oauth2SecurityFilterChain} gives it.
   */
  @Bean
  public AbuseRateLimitFilter abuseRateLimitFilter(
      InMemoryRateLimiter inMemoryRateLimiter,
      ObjectMapper objectMapper,
      RunnerProperties properties) {
    return new AbuseRateLimitFilter(inMemoryRateLimiter, objectMapper, properties);
  }

  /** See {@link #abuseRateLimitFilter}'s own Javadoc for why this must be disabled. */
  @Bean
  public FilterRegistrationBean<AbuseRateLimitFilter> abuseRateLimitFilterRegistration(
      AbuseRateLimitFilter filter) {
    FilterRegistrationBean<AbuseRateLimitFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  /**
   * Explicit {@code @Bean}, same reasoning as {@link #abuseRateLimitFilter} - a bare
   * {@code @Component} {@code Filter} leaks into every unrelated {@code @WebMvcTest} slice, and
   * (see {@link #abuseRateLimitFilterRegistration}) would also auto-register as a bare servlet
   * filter in a real running application without {@link #requestBodySizeLimitFilterRegistration}
   * disabling that.
   */
  @Bean
  public RequestBodySizeLimitFilter requestBodySizeLimitFilter(
      RunnerProperties properties, ObjectMapper objectMapper) {
    return new RequestBodySizeLimitFilter(properties, objectMapper);
  }

  /** See {@link #requestBodySizeLimitFilter}'s own Javadoc for why this must be disabled. */
  @Bean
  public FilterRegistrationBean<RequestBodySizeLimitFilter> requestBodySizeLimitFilterRegistration(
      RequestBodySizeLimitFilter filter) {
    FilterRegistrationBean<RequestBodySizeLimitFilter> registration =
        new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  /**
   * No authentication is configured (default local {@code bootRun}, every existing test), so every
   * request is permitted. CSRF remains enabled because browser cookie/session semantics still apply
   * in local development. Never active in {@code PORTFOLIO} (enforced by {@code
   * RunnerSecurityEnvironmentPostProcessor}, which aborts startup before any listening socket ever
   * opens, so this chain could never serve real production traffic).
   */
  @Bean
  @ConditionalOnProperty(
      name = "runner.security.oauth2-enabled",
      havingValue = "false",
      matchIfMissing = true)
  public SecurityFilterChain permissiveSecurityFilterChain(
      HttpSecurity http,
      CsrfTokenRepository csrfTokenRepository,
      RequestBodySizeLimitFilter requestBodySizeLimitFilter)
      throws Exception {
    return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(csrfTokenRepository)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .cors(CorsConfigurer::disable)
        // Resource protection, not auth-adjacent, so this applies here too, unlike
        // AbuseRateLimitFilter (OAuth2-chain-only).
        .addFilterBefore(requestBodySizeLimitFilter, SecurityContextHolderFilter.class)
        .build();
  }

  /**
   * Default-deny: every existing route is enumerated explicitly (never a broad {@code
   * anyRequest().permitAll()}), so a future mutating endpoint can never become accidentally public
   * by omission. Every route under {@code /api/v1/auth/**} gets its own explicit rule - never one
   * grouped matcher for the whole namespace.
   */
  @Bean
  @ConditionalOnProperty(name = "runner.security.oauth2-enabled", havingValue = "true")
  public SecurityFilterChain oauth2SecurityFilterChain(
      HttpSecurity http,
      GithubOAuth2UserService githubOAuth2UserService,
      ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
      ProblemDetailAccessDeniedHandler accessDeniedHandler,
      CsrfTokenRepository csrfTokenRepository,
      AbuseRateLimitFilter abuseRateLimitFilter,
      RequestBodySizeLimitFilter requestBodySizeLimitFilter)
      throws Exception {
    return http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/runs",
                        "/api/v1/runs/*",
                        "/api/v1/runs/*/log",
                        "/api/v1/runs/*/artifacts",
                        "/api/v1/runs/*/artifacts/*",
                        "/api/v1/runs/*/events",
                        "/api/v1/capabilities",
                        "/api/v1/tests",
                        "/api/v1/auth/me",
                        "/api/v1/auth/csrf",
                        "/actuator/health",
                        // Liveness/readiness probes: Caddy proxies these three exact paths publicly
                        // (and fail-closed rejects every other /actuator/* path) - an
                        // unauthenticated healthcheck caller must never need admin credentials.
                        "/actuator/health/liveness",
                        "/actuator/health/readiness",
                        // Unauthenticated for the same reason, plus: a Prometheus scraper can't
                        // perform an interactive GitHub login. Not reachable externally regardless
                        // (Caddy rejects this path, and runner-service publishes no port).
                        "/actuator/prometheus",
                        "/actuator/info",
                        // Not proxied publicly at all - permitted here so npm run api:export/
                        // api:check:contract can still fetch it from a local bootRun.
                        "/v3/api-docs")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/auth/oauth2/authorization/github",
                        "/api/v1/auth/oauth2/callback/github")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout")
                    .authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/v1/runs", "/api/v1/runs/*/cancel")
                    .hasRole("ADMIN")
                    // Admin-only operational tooling (RetentionController).
                    .requestMatchers(HttpMethod.GET, "/api/v1/retention/preview")
                    .hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/v1/retention/run")
                    .hasRole("ADMIN")
                    // Admin-only operational tooling (DiskUsageController).
                    .requestMatchers(HttpMethod.GET, "/api/v1/disk/usage")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .denyAll())
        .oauth2Login(
            oauth2 ->
                oauth2
                    .authorizationEndpoint(a -> a.baseUri("/api/v1/auth/oauth2/authorization"))
                    .redirectionEndpoint(r -> r.baseUri("/api/v1/auth/oauth2/callback/*"))
                    .userInfoEndpoint(u -> u.userService(githubOAuth2UserService))
                    .successHandler(loginSuccessHandler())
                    .failureHandler(loginFailureHandler()))
        .logout(
            (LogoutConfigurer<HttpSecurity> logout) ->
                logout
                    .logoutUrl("/api/v1/auth/logout")
                    .logoutSuccessHandler(
                        (request, response, authentication) -> response.setStatus(204))
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID"))
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(csrfTokenRepository)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .cors(CorsConfigurer::disable)
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        // After SecurityContextHolderFilter, not AuthorizationFilter: the OAuth2 login-flow
        // filters run and fully commit their response before AuthorizationFilter, so a filter
        // registered after it would never see the oauth-authorization/oauth-callback routes at
        // all. This position still restores Authentication for rate-limit key extraction on an
        // already-authenticated admin's requests, while an anonymous caller on an admin-only route
        // still gets a null key (skipped) and is rejected by AuthorizationFilter as before.
        .addFilterAfter(abuseRateLimitFilter, SecurityContextHolderFilter.class)
        // Resource protection, applies here too regardless of authentication outcome.
        .addFilterBefore(requestBodySizeLimitFilter, SecurityContextHolderFilter.class)
        .build();
  }

  /**
   * Always redirects to a fixed internal path, never an unvalidated {@code returnUrl}/saved-request
   * parameter - deliberately closes off an open-redirect vector.
   */
  private AuthenticationSuccessHandler loginSuccessHandler() {
    SimpleUrlAuthenticationSuccessHandler handler = new SimpleUrlAuthenticationSuccessHandler("/");
    handler.setAlwaysUseDefaultTargetUrl(true);
    return handler;
  }

  private AuthenticationFailureHandler loginFailureHandler() {
    return new SimpleUrlAuthenticationFailureHandler("/");
  }

  /**
   * Only ever constructed when GitHub OAuth2 is enabled - {@link AdminGithubAllowlist#parse} would
   * otherwise reject the empty default {@link RunnerSecurityProperties#adminGithubId()} for the
   * wrong reason (it means "genuinely disabled" here, not "misconfigured"). A malformed value fails
   * this bean's construction at context-refresh time, which is itself the fail-closed check.
   */
  @Bean
  @ConditionalOnProperty(name = "runner.security.oauth2-enabled", havingValue = "true")
  public AdminGithubAllowlist adminGithubAllowlist(RunnerSecurityProperties properties) {
    return AdminGithubAllowlist.parse(properties.adminGithubId());
  }
}
