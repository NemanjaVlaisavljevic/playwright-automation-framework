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
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
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
 * credentials were configured at startup (see {@code RunnerSecurityEnvironmentPostProcessor}) -
 * never both active at once, so there is exactly one answer to "what does this request need" at any
 * time.
 *
 * <p><strong>CORS is deliberately unsupported (D3.3), on both chains</strong> - no {@code
 * CorsConfigurationSource} bean exists anywhere in this service, and {@code
 * .cors(CorsConfigurer::disable)} below makes that a stated, tested decision rather than an
 * implicit default: this API is same-origin-only by design (Caddy in production, Vite's dev proxy
 * locally, always co-locate the SPA and the API under one origin - see {@code
 * runner-dashboard/vite.config.ts}), so no cross-origin browser access to it is ever legitimate.
 * {@code SecurityAccessMatrixTest} proves a cross-origin-shaped request never receives an {@code
 * Access-Control-Allow-Origin} header on either chain.
 *
 * <p><strong>The OAuth2 chain's {@code request.getRemoteAddr()} trust boundary (D3.3)</strong> -
 * {@link AbuseRateLimitFilter}'s client-IP-keyed rate limits are only as trustworthy as that
 * address resolution. {@code deploy/runner-service/Dockerfile} sets {@code
 * SERVER_FORWARD_HEADERS_STRATEGY=native}, which activates Tomcat's own {@code RemoteIpValve} (via
 * {@code server.tomcat.remoteip.internal-proxies}) instead of the plain {@code
 * ForwardedHeaderFilter} the {@code framework} strategy would - the valve only honors {@code
 * X-Forwarded-For}/{@code -Proto}/{@code -Host} when the <em>direct</em> TCP peer matches a trusted
 * (private/Docker-internal) range, which in this topology can only ever be Caddy ( {@code
 * runner-service} has no published port and shares its {@code edge} network with exactly one other
 * container). A plain {@code ForwardedHeaderFilter} has no such concept at all and would parse the
 * header unconditionally regardless of who sent it.
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
   * Deliberately an explicit {@code @Bean} here, not a bare {@code @Component} on the filter class
   * itself - see {@link AbuseRateLimitFilter}'s own Javadoc for why (confirmed empirically:
   * {@code @WebMvcTest} auto-detects and registers any {@code Filter} bean in any slice, breaking
   * entirely unrelated tests the moment this class was a component anywhere on the classpath).
   *
   * <p><strong>That same auto-detection also applies to a real running application, not just
   * {@code @WebMvcTest} (a review finding)</strong>: Spring Boot registers <em>any</em> {@code
   * Filter} bean as a plain servlet-container filter via its own {@code FilterRegistrationBean}
   * autoconfiguration, completely independent of - and in addition to - this bean also being added
   * into a {@code SecurityFilterChain} via {@code .addFilterAfter(...)} below. Left unaddressed,
   * this filter would run as a bare, unconditional, "/*"-scoped servlet filter on every deployment
   * profile - including the permissive/local chain, where it is specifically meant not to apply at
   * all - with its position in that generic chain governed by Boot's own default filter ordering,
   * not by anything declared here. {@link #abuseRateLimitFilterRegistration} disables that
   * automatic registration, so the only place this filter ever actually runs is the exact position
   * {@code oauth2SecurityFilterChain}'s own {@code .addFilterAfter(...)} call gives it.
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
   * Today's behavior, unchanged: no authentication configured at all (default local {@code
   * bootRun}, every existing test) - every request is permitted, CSRF disabled. Never active in
   * {@code PORTFOLIO} (enforced by {@code RunnerSecurityEnvironmentPostProcessor}, which aborts
   * startup - before any listening socket ever opens - the moment that profile is combined with
   * missing OAuth2 credentials, so this chain could never serve real production traffic).
   */
  @Bean
  @ConditionalOnProperty(
      name = "runner.security.oauth2-enabled",
      havingValue = "false",
      matchIfMissing = true)
  public SecurityFilterChain permissiveSecurityFilterChain(
      HttpSecurity http, RequestBodySizeLimitFilter requestBodySizeLimitFilter) throws Exception {
    return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .csrf(CsrfConfigurer::disable)
        .cors(CorsConfigurer::disable)
        // D3.3 - resource protection, not auth-adjacent, so this applies here too, unlike
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
                        "/actuator/info",
                        // Not proxied publicly at all (Caddy only ever forwards /actuator/health
                        // and /api/*) - permitted here purely so npm run api:export/
                        // api:check:contract can still fetch it from a local bootRun once GitHub
                        // OAuth2 is enabled, the same tooling this repo already relies on today.
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
        // D3.3 - after SecurityContextHolderFilter (which restores an existing session's
        // Authentication, if any), not after AuthorizationFilter as first designed - confirmed
        // live that OAuth2AuthorizationRequestRedirectFilter/OAuth2LoginAuthenticationFilter
        // (which actually handle the two OAuth2 login-flow routes) both run, and fully commit
        // their response, well before AuthorizationFilter - a filter registered after
        // AuthorizationFilter is never reached at all for those two routes, so the
        // oauth-authorization/oauth-callback limits would silently never apply. Registering here
        // instead still lets ADMIN_GITHUB_ID key extraction work correctly for an
        // already-authenticated admin's own subsequent requests (Authentication is restored by
        // SecurityContextHolderFilter, not decided by AuthorizationFilter), while an anonymous
        // caller hitting an admin-only route still gets a null key (skipped, not rate-limited) and
        // is rejected by AuthorizationFilter exactly as before - no change to that outcome.
        .addFilterAfter(abuseRateLimitFilter, SecurityContextHolderFilter.class)
        // D3.3 - resource protection, applies here too regardless of authentication outcome.
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
   * wrong reason (it means "genuinely disabled" here, not "misconfigured"; a genuinely malformed
   * value fails this bean's own construction at context-refresh time, which is itself the
   * fail-closed check for this value - see {@code RunnerSecurityEnvironmentPostProcessor} for the
   * earlier, pre-context checks on the rest of the OAuth2 configuration).
   */
  @Bean
  @ConditionalOnProperty(name = "runner.security.oauth2-enabled", havingValue = "true")
  public AdminGithubAllowlist adminGithubAllowlist(RunnerSecurityProperties properties) {
    return AdminGithubAllowlist.parse(properties.adminGithubId());
  }
}
