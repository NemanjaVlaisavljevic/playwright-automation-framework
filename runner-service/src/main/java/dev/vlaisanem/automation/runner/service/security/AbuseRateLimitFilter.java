package dev.vlaisanem.automation.runner.service.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.security.InMemoryRateLimiter.NamedRule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One filter enforcing the whole D3.3 rate-limit matrix (see {@code RunnerProperties}'s own D3.3
 * Javadoc for the per-surface rationale) - never a single global limit, since different surfaces
 * have very different real costs. Registered only in the OAuth2 chain, right after {@code
 * SecurityContextHolderFilter} (see {@code SecurityConfig}) - <strong>not</strong> after {@code
 * AuthorizationFilter}, confirmed live: the two OAuth2 login-flow routes are actually handled (and
 * their response fully committed) by {@code OAuth2AuthorizationRequestRedirectFilter}/{@code
 * OAuth2LoginAuthenticationFilter}, both of which run well before {@code AuthorizationFilter} - a
 * filter registered after it is simply never reached for those two routes at all, which the first
 * version of this class got wrong (its rate limits silently never applied there). Registering here
 * instead still gets the same, correct outcome for the admin-keyed surfaces: an anonymous/non-admin
 * caller hitting an admin-only route has no numeric-id key to rate-limit against (see {@link
 * KeyStrategy#ADMIN_GITHUB_ID}, which returns {@code null} for a non-{@code OAuth2User} principal)
 * and is simply skipped here, then still correctly rejected with 401/403 later by {@code
 * AuthorizationFilter} - never rate-limited on top of that, exactly as intended; only an
 * already-authenticated admin's own repeated calls (their session's {@code Authentication} is
 * already restored by {@code SecurityContextHolderFilter} by the time this runs) are ever counted.
 *
 * <p>Anonymous surfaces (OAuth login, public reads, downloads) are keyed by client IP; the two
 * authenticated-admin mutation surfaces (create/cancel run) are keyed by the caller's GitHub
 * numeric id (never username) - the same attribute {@code GithubOAuth2UserService}/{@code
 * CurrentUserController} already read.
 *
 * <p>{@code request.getRemoteAddr()} is only a trustworthy per-IP key because of the {@code
 * RemoteIpValve} trust boundary described in {@code SecurityConfig}'s own D3.3 notes - this class
 * does not itself re-verify that boundary, it simply relies on the container having already
 * resolved the address correctly.
 *
 * <p>Deliberately not a bare {@code @Component}: {@code @WebMvcTest} auto-detects and registers any
 * {@code Filter} bean it finds, in <em>any</em> slice, regardless of whether that slice imports
 * anything related to security at all (confirmed empirically - it broke {@code
 * RunEventStreamControllerTest}, an entirely unrelated slice, the moment this class existed as a
 * component anywhere on the classpath). Constructed as an explicit {@code @Bean} in {@link
 * SecurityConfig} instead, so only a test that actually imports {@code SecurityConfig} ever sees
 * it.
 */
public class AbuseRateLimitFilter extends OncePerRequestFilter {

  private final InMemoryRateLimiter rateLimiter;
  private final ObjectMapper objectMapper;
  private final List<Surface> surfaces;
  private final AntPathMatcher pathMatcher = new AntPathMatcher();

  public AbuseRateLimitFilter(
      InMemoryRateLimiter rateLimiter, ObjectMapper objectMapper, RunnerProperties properties) {
    this.rateLimiter = rateLimiter;
    this.objectMapper = objectMapper;
    this.surfaces =
        List.of(
            new Surface(
                HttpMethod.GET,
                List.of("/api/v1/auth/oauth2/authorization/github"),
                KeyStrategy.CLIENT_IP,
                List.of(
                    new NamedRule(
                        "oauth-authorization", properties.oauthAuthorizationRateLimit()))),
            new Surface(
                HttpMethod.GET,
                List.of("/api/v1/auth/oauth2/callback/github"),
                KeyStrategy.CLIENT_IP,
                List.of(new NamedRule("oauth-callback", properties.oauthCallbackRateLimit()))),
            new Surface(
                HttpMethod.POST,
                List.of("/api/v1/runs"),
                KeyStrategy.ADMIN_GITHUB_ID,
                // Both rules are evaluated atomically by InMemoryRateLimiter.tryAcquire - a
                // request rejected by the hourly rule never silently consumes the minute rule's
                // budget (a review finding against an earlier version that checked/incremented
                // them one at a time).
                List.of(
                    new NamedRule(
                        "create-run-per-minute", properties.createRunRateLimitPerMinute()),
                    new NamedRule("create-run-per-hour", properties.createRunRateLimitPerHour()))),
            new Surface(
                HttpMethod.POST,
                List.of("/api/v1/runs/*/cancel"),
                KeyStrategy.ADMIN_GITHUB_ID,
                List.of(new NamedRule("cancel-run", properties.cancelRunRateLimit()))),
            new Surface(
                HttpMethod.GET,
                List.of(
                    "/api/v1/runs/*/log", "/api/v1/runs/*/artifacts", "/api/v1/runs/*/artifacts/*"),
                KeyStrategy.CLIENT_IP,
                List.of(new NamedRule("download", properties.downloadRateLimit()))),
            new Surface(
                HttpMethod.GET,
                List.of("/api/v1/runs", "/api/v1/runs/*", "/api/v1/capabilities", "/api/v1/tests"),
                KeyStrategy.CLIENT_IP,
                List.of(new NamedRule("public-read", properties.publicReadRateLimit()))),
            // D4.1 review round: a real sweep does real DB/filesystem work, so both retention
            // routes get their own conservative admin-keyed limit like every other admin-only
            // mutation surface above - each tracked as its own independent counter (a valid or
            // stolen admin session could otherwise trigger dry-run previews and real sweeps as
            // often as it likes).
            new Surface(
                HttpMethod.GET,
                List.of("/api/v1/retention/preview"),
                KeyStrategy.ADMIN_GITHUB_ID,
                List.of(new NamedRule("retention-preview", properties.retentionRateLimit()))),
            new Surface(
                HttpMethod.POST,
                List.of("/api/v1/retention/run"),
                KeyStrategy.ADMIN_GITHUB_ID,
                List.of(new NamedRule("retention-run", properties.retentionRateLimit()))));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    Surface matched = matchSurface(request);
    if (matched == null) {
      filterChain.doFilter(request, response);
      return;
    }
    String key = matched.keyStrategy.extractKey(request);
    if (key == null) {
      // Expected, not just defensive, for the admin-keyed surfaces: this filter runs before
      // AuthorizationFilter's own ROLE_ADMIN decision (see this class's own Javadoc for why), so
      // an anonymous or non-admin caller genuinely has no numeric id to key against here - skipped
      // rather than blocked, and still correctly rejected with 401/403 by AuthorizationFilter
      // afterward. Never block a request this filter cannot key.
      filterChain.doFilter(request, response);
      return;
    }
    InMemoryRateLimiter.Result result = rateLimiter.tryAcquire(key, matched.rules);
    if (!result.allowed()) {
      writeRejection(request, response, result.retryAfter());
      return;
    }
    filterChain.doFilter(request, response);
  }

  private Surface matchSurface(HttpServletRequest request) {
    HttpMethod method = HttpMethod.valueOf(request.getMethod());
    String path = request.getRequestURI();
    for (Surface surface : surfaces) {
      if (surface.method != method) {
        continue;
      }
      for (String pattern : surface.pathPatterns) {
        if (pathMatcher.match(pattern, path)) {
          return surface;
        }
      }
    }
    return null;
  }

  private void writeRejection(
      HttpServletRequest request, HttpServletResponse response, Duration retryAfter)
      throws IOException {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.TOO_MANY_REQUESTS, "Too many requests - try again later.");
    problem.setInstance(URI.create(request.getRequestURI()));
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(ceilSecondsAtLeastOne(retryAfter)));
    objectMapper.writeValue(response.getWriter(), problem);
  }

  /**
   * {@code Duration.toSeconds()} truncates - a review finding: 59.9 remaining seconds would floor
   * to {@code Retry-After: 59}, advising a client to retry slightly before the window actually
   * elapses. Rounds up instead, and never below 1 (a {@code Retry-After: 0} is meaningless).
   */
  static long ceilSecondsAtLeastOne(Duration duration) {
    long wholeSeconds = duration.toSeconds();
    long remainderNanos = duration.minusSeconds(wholeSeconds).toNanos();
    long seconds = remainderNanos > 0 ? wholeSeconds + 1 : wholeSeconds;
    return Math.max(1, seconds);
  }

  private record Surface(
      HttpMethod method,
      List<String> pathPatterns,
      KeyStrategy keyStrategy,
      List<NamedRule> rules) {}

  private enum KeyStrategy {
    CLIENT_IP {
      @Override
      String extractKey(HttpServletRequest request) {
        return request.getRemoteAddr();
      }
    },
    ADMIN_GITHUB_ID {
      @Override
      String extractKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication != null && authentication.getPrincipal() instanceof OAuth2User user)) {
          return null;
        }
        Object id = user.getAttribute("id");
        return id == null ? null : id.toString();
      }
    };

    abstract String extractKey(HttpServletRequest request);
  }
}
