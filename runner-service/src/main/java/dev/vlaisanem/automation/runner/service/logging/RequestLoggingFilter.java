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
 * Adds a validated, echoed {@code X-Request-ID}, MDC correlation, and one HTTP access-log line
 * (method/route/status/duration/outcome) per request, regardless of which {@code SecurityConfig}
 * chain is active. Ordered ahead of Spring Security ({@link Order @Order(HIGHEST_PRECEDENCE)}) so
 * even a security-rejected request gets a request id and an access-log line with its real status.
 *
 * <p>Each request logs exactly once, via a bounded {@link Outcome}: sync completion logs {@link
 * Outcome#COMPLETED}; async/SSE requests register an {@link AsyncListener} and log from {@code
 * onComplete}/{@code onError}/{@code onTimeout}; an exception logs {@link Outcome#EXCEPTION} from
 * this method's own {@code finally} so the original exception still propagates unchanged.
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
      // The async cycle can complete between isAsyncStarted() and this call (fast terminal SSE
      // replay), leaving no AsyncContext to attach to - log immediately instead of letting an
      // observability-only failure escape as a request-processing error.
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
    // MDC is thread-local and the async callback may run on a different thread than doFilter, so
    // it's re-established here rather than assumed to still be set.
    try (MdcScope.Handle ignored = MdcScope.open(MDC_REQUEST_ID_KEY, requestId)) {
      // nanoTime, not wall-clock Instant, so a mid-request clock/NTP adjustment can't skew this.
      long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
      String route = routeTemplate(request);
      int status = resolveStatus(response, outcome);
      // Successful health-probe polls are frequent and low-signal; log those at DEBUG only.
      boolean quiet =
          outcome == Outcome.COMPLETED && isSuccessfulHealthProbe(request.getRequestURI(), status);
      LoggingEventBuilder entry = quiet ? log.atDebug() : log.atInfo();
      // Only method/route/status/duration/outcome are read here - never the request body or any
      // header (Authorization, Cookie, OAuth code/state).
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
    // TIMEOUT/ERROR/EXCEPTION: the container may not have committed a final status yet, so this is
    // best-effort (the `outcome` field tells a reader whether it was observed or inferred).
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
   * {@code onComplete}/{@code onError} can both fire for the same request - the shared {@link
   * AtomicBoolean} guard in {@code logAccessLineOnce} keeps the access line to exactly one.
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
      // The container doesn't carry this listener over to a new async cycle on the same request,
      // so a second startAsync() must re-register it here or the request logs nothing at all.
      event.getAsyncContext().addListener(this);
    }
  }
}
