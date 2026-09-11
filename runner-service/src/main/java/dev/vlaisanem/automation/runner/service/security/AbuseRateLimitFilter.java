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
 * One filter enforcing the whole rate-limit matrix - never a single global limit, since different
 * surfaces have very different real costs. Registered only in the OAuth2 chain, right after {@code
 * SecurityContextHolderFilter}, not after {@code AuthorizationFilter}: the two OAuth2 login-flow
 * routes are handled (and their response fully committed) before {@code AuthorizationFilter} runs,
 * so a filter registered after it would never see them. For the admin-keyed surfaces, an
 * anonymous/non-admin caller has no numeric-id key (see {@link KeyStrategy#ADMIN_GITHUB_ID}) and is
 * simply skipped here, then still correctly rejected with 401/403 by {@code AuthorizationFilter}.
 *
 * <p>Anonymous surfaces (OAuth login, public reads, downloads) are keyed by client IP; the two
 * authenticated-admin mutation surfaces (create/cancel run) are keyed by the caller's GitHub
 * numeric id, never username.
 *
 * <p>{@code request.getRemoteAddr()} is only a trustworthy per-IP key because of the {@code
 * RemoteIpValve} trust boundary described in {@code SecurityConfig} - this class relies on the
 * container having already resolved the address correctly.
 *
 * <p>Deliberately not a bare {@code @Component}: {@code @WebMvcTest} auto-detects and registers any
 * {@code Filter} bean it finds in any slice, breaking unrelated tests. Constructed as an explicit
 * {@code @Bean} in {@link SecurityConfig} instead, so only a test importing it ever sees this.
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
                // budget.
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
            // A real sweep does real DB/filesystem work, so both retention routes get their own
            // conservative admin-keyed limit, each its own independent counter.
            new Surface(
                HttpMethod.GET,
                List.of("/api/v1/retention/preview"),
                KeyStrategy.ADMIN_GITHUB_ID,
                List.of(new NamedRule("retention-preview", properties.retentionRateLimit()))),
            new Surface(
                HttpMethod.POST,
                List.of("/api/v1/retention/run"),
                KeyStrategy.ADMIN_GITHUB_ID,
                List.of(new NamedRule("retention-run", properties.retentionRateLimit()))),
            // A filesystem-tree walk plus a live Postgres size query is real work, so this
            // admin-only diagnostic route gets its own conservative rate limit too.
            new Surface(
                HttpMethod.GET,
                List.of("/api/v1/disk/usage"),
                KeyStrategy.ADMIN_GITHUB_ID,
                List.of(new NamedRule("disk-usage", properties.diskUsageRateLimit()))));
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
      // AuthorizationFilter's ROLE_ADMIN decision, so an anonymous/non-admin caller genuinely has
      // no numeric id to key against - skipped here, still correctly rejected later.
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
   * {@code Duration.toSeconds()} truncates, so 59.9 remaining seconds would floor to {@code
   * Retry-After: 59} and advise retrying too early. Rounds up instead, never below 1.
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
