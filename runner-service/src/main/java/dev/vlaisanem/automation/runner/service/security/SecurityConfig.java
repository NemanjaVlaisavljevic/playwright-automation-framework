package dev.vlaisanem.automation.runner.service.security;

import dev.vlaisanem.automation.runner.service.config.RunnerSecurityProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.annotation.web.configurers.LogoutConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Two mutually exclusive {@link SecurityFilterChain} beans, selected by whether GitHub OAuth2
 * credentials were configured at startup (see {@code RunnerSecurityEnvironmentPostProcessor}) -
 * never both active at once, so there is exactly one answer to "what does this request need" at any
 * time.
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
   * Today's behavior, unchanged: no authentication configured at all (default local {@code
   * bootRun}, every existing test) - every request is permitted, CSRF disabled. Never active in
   * {@code PORTFOLIO} (enforced by {@link RunnerSecurityEnvironmentValidator}, which aborts startup
   * before this could ever serve real production traffic).
   */
  @Bean
  @ConditionalOnProperty(
      name = "runner.security.oauth2-enabled",
      havingValue = "false",
      matchIfMissing = true)
  public SecurityFilterChain permissiveSecurityFilterChain(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .csrf(CsrfConfigurer::disable)
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
      CsrfTokenRepository csrfTokenRepository)
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
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
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
   * wrong reason (it means "genuinely disabled" here, not "misconfigured"; see {@link
   * RunnerSecurityEnvironmentValidator} for the real fail-closed check on this same value).
   */
  @Bean
  @ConditionalOnProperty(name = "runner.security.oauth2-enabled", havingValue = "true")
  public AdminGithubAllowlist adminGithubAllowlist(RunnerSecurityProperties properties) {
    return AdminGithubAllowlist.parse(properties.adminGithubId());
  }
}
