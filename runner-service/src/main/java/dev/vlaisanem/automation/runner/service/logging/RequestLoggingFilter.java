package dev.vlaisanem.automation.runner.service.logging;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * D4.3.3 - a validated, echoed {@code X-Request-ID} plus MDC correlation and a real HTTP access-log
 * line (method/route template/status/duration/outcome), for every request regardless of which
 * {@code SecurityConfig} chain is active. A plain {@code @Component} {@code Filter}, deliberately
 * <em>not</em> disabled the way {@code AbuseRateLimitFilter}/{@code RequestBodySizeLimitFilter} are
 * - unlike those two, this filter's own behavior never differs per chain, and it must wrap Spring
 * Security's own processing (via {@link Order @Order(Ordered.HIGHEST_PRECEDENCE)}) so a
 * security-rejected request still gets a real {@code requestId} and access-log line with its real
 * final status.
 *
 * <p><strong>Every completion path logs exactly once, with a bounded {@link Outcome}</strong> (a
 * review finding): a normal synchronous return logs {@link Outcome#COMPLETED}; an SSE/async request
 * (a real {@code SseEmitter} response starts async processing and returns from {@code
 * filterChain.doFilter} long before the connection actually closes) registers an {@link
 * AsyncListener} instead and logs {@link Outcome#COMPLETED}/{@link Outcome#ERROR}/{@link
 * Outcome#TIMEOUT} from {@code onComplete}/{@code onError}/{@code onTimeout} respectively - never
 * conflated into one, since a timed-out or errored SSE connection must never read as an ordinary
 * successful completion in the log; an exception propagating out of {@code filterChain.doFilter}
 * logs {@link Outcome#EXCEPTION} from this method's own {@code finally} block (never a separate
 * {@code catch}+rethrow) so the original exception is always re-propagated unchanged and the access
 * line is still written exactly once even though the container's own eventual error handling has
 * not run yet at that point. Every non-{@code COMPLETED} outcome's {@code status} field is
 * best-effort ({@code response.isCommitted() ? response.getStatus() : 500}) - the real {@code
 * outcome} field always tells a reader whether that status was actually observed or only inferred.
 *
 * <p><strong>The async cycle can complete between {@code isAsyncStarted()} and listener
 * registration</strong> (a review finding, realistic for a fast terminal SSE replay) - {@code
 * request.getAsyncContext()}/{@code addListener} then throw {@link IllegalStateException}. Caught
 * here and converted into an immediate best-effort {@link Outcome#COMPLETED} log rather than
 * letting an observability-only failure escape and turn into a real request-processing error.
 *
 * <p><strong>A further {@code startAsync()} restarts the cycle without keeping this listener
 * registered</strong> (a review finding) - the container does not carry a previously-registered
 * {@link AsyncListener} over to a new async cycle on the same request, so {@link
 * AccessLogAsyncListener#onStartAsync} re-registers itself on the new {@code AsyncContext}; without
 * this, a request that calls {@code startAsync()} a second time would finish with zero access-log
 * lines at all.
 *
 * <p><strong>Never logs the request body or any header</strong> - so never {@code
 * Authorization}/{@code Cookie}/an OAuth {@code code}/{@code state} - only
 * method/route-template/status/duration/outcome are ever read, by construction, not by a redaction
 * step.
 *
 * <p>{@code durationMs} is computed from {@link System#nanoTime()}, never wall-clock {@code
 * Instant} subtraction - immune to a system clock/NTP adjustment mid-request.
 *
 * <p>A successful (2xx) response on either health-probe route logs at {@code DEBUG} instead of
 * {@code INFO} - a real Docker healthcheck polls both every 10s (D4.3.1), and logging every one at
 * {@code INFO} would let identical {@code 200 UP} lines dominate a low-traffic portfolio app's
 * entire rotated log budget. A *failing* probe response still logs at {@code INFO} - a real signal
 * worth seeing.
 *
 * <p><strong>MDC is restored, never blindly cleared</strong> (a review finding) - both this
 * method's own outer scope and {@link #logAccessLineOnce}'s independent inner scope use {@link
 * MdcScope#open}, so a thread that already carried a {@code requestId} from some outer context (a
 * nested dispatch, a reused thread pool worker) has that exact prior value restored afterward,
 * rather than being left with no value at all.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
  private static final String REQUEST_ID_HEADER = "X-Request-ID";
  private static final String MDC_REQUEST_ID_KEY = "requestId";
  private static final Pattern VALID_REQUEST_ID = Pattern.compile("^[a-zA-Z0-9-]{1,64}$");
  private static final Set<String> HEALTH_PROBE_ROUTES =
      Set.of("/actuator/health/liveness", "/actuator/health/readiness");

  /** A bounded, explicit completion classification - never inferred from a status code alone. */
  private enum Outcome {
    COMPLETED,
    TIMEOUT,
    ERROR,
    EXCEPTION
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestId = resolveRequestId(request);
    response.setHeader(REQUEST_ID_HEADER, requestId);
    long startNanos = System.nanoTime();
    AtomicBoolean logged = new AtomicBoolean(false);
    boolean completedNormally = false;
    try (MdcScope.Handle ignored = MdcScope.open(MDC_REQUEST_ID_KEY, requestId)) {
      filterChain.doFilter(request, response);
      completedNormally = true;
    } finally {
      if (request.isAsyncStarted()) {
        registerAsyncListenerOrLogImmediately(request, response, requestId, startNanos, logged);
      } else {
        logAccessLineOnce(
            request,
            response,
            requestId,
            startNanos,
            logged,
            completedNormally ? Outcome.COMPLETED : Outcome.EXCEPTION);
      }
    }
  }

  private static void registerAsyncListenerOrLogImmediately(
      HttpServletRequest request,
      HttpServletResponse response,
      String requestId,
      long startNanos,
      AtomicBoolean logged) {
    try {
      request
          .getAsyncContext()
          .addListener(
              new AccessLogAsyncListener(request, response, requestId, startNanos, logged));
    } catch (IllegalStateException raceLostToCompletion) {
      // The async cycle can complete between isAsyncStarted() (checked by this method's caller)
      // and this call - a real, observed race, not a hypothetical, most realistic for a fast
      // terminal SSE replay - leaving no live AsyncContext left to attach to. Recovered with a
      // best-effort immediate log rather than letting an observability-only failure escape and
      // turn into a request-processing error; COMPLETED is the correct classification since a
      // completed-before-registration cycle is, by definition, not an error or a timeout.
      logAccessLineOnce(request, response, requestId, startNanos, logged, Outcome.COMPLETED);
    }
  }

  private String resolveRequestId(HttpServletRequest request) {
    String provided = request.getHeader(REQUEST_ID_HEADER);
    if (provided != null && VALID_REQUEST_ID.matcher(provided).matches()) {
      return provided;
    }
    return UUID.randomUUID().toString();
  }

  private static void logAccessLineOnce(
      HttpServletRequest request,
      HttpServletResponse response,
      String requestId,
      long startNanos,
      AtomicBoolean logged,
      Outcome outcome) {
    if (!logged.compareAndSet(false, true)) {
      return;
    }
    // The async listener's callback may run on a different thread than the one that entered this
    // filter - MDC is thread-local, so it is re-established here, independently of the outer
    // scope's own, for the duration of this one log call rather than assumed to still be set.
    try (MdcScope.Handle ignored = MdcScope.open(MDC_REQUEST_ID_KEY, requestId)) {
      long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
      String route = routeTemplate(request);
      int status = resolveStatus(response, outcome);
      boolean quiet =
          outcome == Outcome.COMPLETED && isSuccessfulHealthProbe(request.getRequestURI(), status);
      LoggingEventBuilder entry = quiet ? log.atDebug() : log.atInfo();
      entry
          .addKeyValue("method", request.getMethod())
          .addKeyValue("route", route)
          .addKeyValue("status", status)
          .addKeyValue("durationMs", durationMs)
          .addKeyValue("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .log("HTTP request completed");
    }
  }

  private static int resolveStatus(HttpServletResponse response, Outcome outcome) {
    if (outcome == Outcome.COMPLETED) {
      return response.getStatus();
    }
    // TIMEOUT/ERROR/EXCEPTION - the container's own final status handling may not have run yet (an
    // exception's own async error handling), or may never commit one at all (a torn-down SSE
    // connection on timeout) - best-effort: the real status if one was actually committed, else the
    // conventional inferred failure status. The explicit `outcome` field above is what tells a
    // reader this status was inferred, not directly observed.
    return response.isCommitted() ? response.getStatus() : 500;
  }

  private static String routeTemplate(HttpServletRequest request) {
    Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    return pattern != null ? pattern.toString() : request.getRequestURI();
  }

  private static boolean isSuccessfulHealthProbe(String requestUri, int status) {
    return HEALTH_PROBE_ROUTES.contains(requestUri) && status >= 200 && status < 300;
  }

  /**
   * {@code onComplete}/{@code onError} can both fire for the same async request - {@code
   * logAccessLineOnce}'s own {@link AtomicBoolean} guard, shared across all three callbacks here,
   * is what keeps the access line to exactly one regardless of which combination actually fires.
   */
  private static final class AccessLogAsyncListener implements AsyncListener {

    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final String requestId;
    private final long startNanos;
    private final AtomicBoolean logged;

    private AccessLogAsyncListener(
        HttpServletRequest request,
        HttpServletResponse response,
        String requestId,
        long startNanos,
        AtomicBoolean logged) {
      this.request = request;
      this.response = response;
      this.requestId = requestId;
      this.startNanos = startNanos;
      this.logged = logged;
    }

    @Override
    public void onComplete(AsyncEvent event) {
      logAccessLineOnce(request, response, requestId, startNanos, logged, Outcome.COMPLETED);
    }

    @Override
    public void onTimeout(AsyncEvent event) {
      logAccessLineOnce(request, response, requestId, startNanos, logged, Outcome.TIMEOUT);
    }

    @Override
    public void onError(AsyncEvent event) {
      logAccessLineOnce(request, response, requestId, startNanos, logged, Outcome.ERROR);
    }

    @Override
    public void onStartAsync(AsyncEvent event) throws IOException {
      // A further async dispatch restarted async processing on the same request - the container
      // does NOT keep this listener registered for the new cycle automatically (a review finding);
      // without re-registering here explicitly, a request that calls startAsync() a second time
      // would finish with zero access-log lines at all.
      event.getAsyncContext().addListener(this);
    }
  }
}
