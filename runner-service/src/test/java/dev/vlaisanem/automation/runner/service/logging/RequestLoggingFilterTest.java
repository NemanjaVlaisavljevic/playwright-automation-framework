package dev.vlaisanem.automation.runner.service.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerMapping;

class RequestLoggingFilterTest {

  private final RequestLoggingFilter filter = new RequestLoggingFilter();
  private Logger logger;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void attachLogAppender() {
    logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    // The effective root level in a test run is typically INFO, which would silently drop a DEBUG
    // event before it ever reaches the appender below - explicit here so the health-probe DEBUG
    // assertion actually observes the event, not an artifact of this logger's own default level.
    logger.setLevel(Level.DEBUG);
    logAppender = new ListAppender<>();
    logAppender.start();
    logger.addAppender(logAppender);
  }

  @AfterEach
  void detachLogAppenderAndClearMdc() {
    logger.detachAppender(logAppender);
    logger.setLevel(null); // restore inheriting from the root logger - Logback's context is a
    // JVM-wide singleton, so an explicit level set here would otherwise leak into other test
    // classes sharing this same logger.
    MDC.clear();
  }

  @Test
  void generatesARequestIdWhenNoneSupplied() throws Exception {
    HttpServletRequest request = syncRequest("GET", "/api/v1/runs", null);
    HttpServletResponse response = mockResponse(200);
    FilterChain chain = (req, res) -> {};

    filter.doFilter(request, response, chain);

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(response).setHeader(eq("X-Request-ID"), captor.capture());
    assertThat(captor.getValue()).isNotBlank();
  }

  @Test
  void reusesAValidlyShapedSuppliedRequestId() throws Exception {
    HttpServletRequest request = syncRequest("GET", "/api/v1/runs", "client-supplied-id-123");
    HttpServletResponse response = mockResponse(200);
    FilterChain chain = (req, res) -> {};

    filter.doFilter(request, response, chain);

    verify(response).setHeader("X-Request-ID", "client-supplied-id-123");
  }

  @Test
  void regeneratesForAnInvalidSuppliedRequestId() throws Exception {
    // Contains a space and an illegal character - must not be trusted into a header/log line as-is.
    HttpServletRequest request = syncRequest("GET", "/api/v1/runs", "not valid!/id");
    HttpServletResponse response = mockResponse(200);
    FilterChain chain = (req, res) -> {};

    filter.doFilter(request, response, chain);

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(response).setHeader(eq("X-Request-ID"), captor.capture());
    assertThat(captor.getValue()).isNotEqualTo("not valid!/id");
  }

  @Test
  void logsExactlyOnceForANormalSynchronousRequestAndClearsMdcAfterward() throws Exception {
    HttpServletRequest request = syncRequest("GET", "/api/v1/runs", null);
    HttpServletResponse response = mockResponse(200);
    FilterChain chain = (req, res) -> {};

    filter.doFilter(request, response, chain);

    assertThat(logAppender.list).hasSize(1);
    ILoggingEvent event = logAppender.list.get(0);
    assertThat(event.getLevel()).isEqualTo(Level.INFO);
    Map<String, Object> pairs = keyValuePairs(event);
    assertThat(pairs.get("method")).isEqualTo("GET");
    assertThat(pairs.get("route")).isEqualTo("/api/v1/runs");
    assertThat(pairs.get("status")).isEqualTo(200);
    assertThat(MDC.get("requestId")).isNull();
  }

  @Test
  void anAsyncRequestLogsNothingUntilOnCompleteFiresAndThenExactlyOnce() throws Exception {
    HttpServletRequest request = asyncRequest("/api/v1/runs/run-1/events");
    AsyncContext asyncContext = mock(AsyncContext.class);
    when(request.getAsyncContext()).thenReturn(asyncContext);
    ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);

    HttpServletResponse response = mockResponse(200);
    FilterChain chain = (req, res) -> {}; // simulates chain.doFilter returning once async starts

    filter.doFilter(request, response, chain);

    verify(asyncContext).addListener(listenerCaptor.capture());
    assertThat(logAppender.list).as("must not log while the connection is still open").isEmpty();

    AsyncListener listener = listenerCaptor.getValue();
    AsyncEvent event = mock(AsyncEvent.class);
    listener.onComplete(event);

    assertThat(logAppender.list).hasSize(1);
    assertThat(keyValuePairs(logAppender.list.get(0)).get("outcome")).isEqualTo("completed");

    listener.onError(event); // both onComplete and onError can fire for the same request

    assertThat(logAppender.list).as("must never log a second line").hasSize(1);
  }

  /**
   * D4.3.3 review finding [P1] - the async cycle can complete between {@code isAsyncStarted()} and
   * listener registration (realistic for a fast terminal SSE replay), so {@code getAsyncContext()}/
   * {@code addListener} then throw {@link IllegalStateException}. This must never escape the filter
   * and turn a best-effort observability failure into a real request-processing error - recovered
   * with an immediate best-effort log instead.
   */
  @Test
  void anAsyncContextThatAlreadyCompletedBeforeListenerRegistrationStillLogsOnceAndNeverThrows()
      throws Exception {
    HttpServletRequest request = asyncRequest("/api/v1/runs/run-1/events");
    AsyncContext asyncContext = mock(AsyncContext.class);
    when(request.getAsyncContext()).thenReturn(asyncContext);
    doThrow(new IllegalStateException("already completed")).when(asyncContext).addListener(any());
    HttpServletResponse response = mockResponse(200);
    FilterChain chain = (req, res) -> {};

    filter.doFilter(request, response, chain);

    assertThat(logAppender.list).hasSize(1);
    Map<String, Object> pairs = keyValuePairs(logAppender.list.get(0));
    assertThat(pairs.get("status")).isEqualTo(200);
    assertThat(pairs.get("outcome")).isEqualTo("completed");
  }

  /**
   * D4.3.3 review finding [P1] - the container does not keep this listener registered for a new
   * async cycle on the same request; without re-registering in {@code onStartAsync}, a request that
   * calls {@code startAsync()} a second time would finish with zero access-log lines at all.
   */
  @Test
  void onStartAsyncReRegistersItselfSoASecondAsyncCycleStillLogsExactlyOnce() throws Exception {
    HttpServletRequest request = asyncRequest("/api/v1/runs/run-1/events");
    AsyncContext firstCycleContext = mock(AsyncContext.class);
    when(request.getAsyncContext()).thenReturn(firstCycleContext);
    ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);
    HttpServletResponse response = mockResponse(200);

    filter.doFilter(request, response, (req, res) -> {});
    verify(firstCycleContext).addListener(listenerCaptor.capture());
    AsyncListener listener = listenerCaptor.getValue();

    AsyncContext secondCycleContext = mock(AsyncContext.class);
    AsyncEvent restart = mock(AsyncEvent.class);
    when(restart.getAsyncContext()).thenReturn(secondCycleContext);
    listener.onStartAsync(restart);

    verify(secondCycleContext).addListener(listener);
    assertThat(logAppender.list)
        .as("still nothing until the new cycle actually completes")
        .isEmpty();

    listener.onComplete(mock(AsyncEvent.class));

    assertThat(logAppender.list).hasSize(1);
  }

  /**
   * D4.3.3 review finding [P2] - {@code onComplete}/{@code onTimeout}/{@code onError} must not be
   * conflated into the same outcome; a timed-out SSE connection logging as an ordinary successful
   * completion (often even with a stale {@code 200}) would be actively misleading.
   */
  @Test
  void anAsyncTimeoutLogsAsTimeoutNeverAsAnOrdinaryCompletion() throws Exception {
    HttpServletRequest request = asyncRequest("/api/v1/runs/run-1/events");
    AsyncContext asyncContext = mock(AsyncContext.class);
    when(request.getAsyncContext()).thenReturn(asyncContext);
    ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);
    HttpServletResponse response = mockResponse(200); // stale/misleading - never actually committed
    when(response.isCommitted()).thenReturn(false);

    filter.doFilter(request, response, (req, res) -> {});
    verify(asyncContext).addListener(listenerCaptor.capture());
    listenerCaptor.getValue().onTimeout(mock(AsyncEvent.class));

    assertThat(logAppender.list).hasSize(1);
    Map<String, Object> pairs = keyValuePairs(logAppender.list.get(0));
    assertThat(pairs.get("outcome")).isEqualTo("timeout");
    assertThat(pairs.get("status"))
        .as("never the stale 200 - the real status was never actually committed")
        .isEqualTo(500);
  }

  @Test
  void anAsyncErrorLogsAsErrorNeverAsAnOrdinaryCompletion() throws Exception {
    HttpServletRequest request = asyncRequest("/api/v1/runs/run-1/events");
    AsyncContext asyncContext = mock(AsyncContext.class);
    when(request.getAsyncContext()).thenReturn(asyncContext);
    ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);
    HttpServletResponse response = mockResponse(200);
    when(response.isCommitted()).thenReturn(false);

    filter.doFilter(request, response, (req, res) -> {});
    verify(asyncContext).addListener(listenerCaptor.capture());
    listenerCaptor.getValue().onError(mock(AsyncEvent.class));

    assertThat(logAppender.list).hasSize(1);
    Map<String, Object> pairs = keyValuePairs(logAppender.list.get(0));
    assertThat(pairs.get("outcome")).isEqualTo("error");
    assertThat(pairs.get("status")).isEqualTo(500);
  }

  /**
   * D4.3.3 review finding [P2] - a blind {@code MDC.remove} would erase a {@code requestId} some
   * outer scope had already put there before this filter ever ran. {@code MdcScope}'s restore-not-
   * remove semantics must apply here too, exactly as it already does at every background-thread
   * boundary this phase touches.
   */
  @Test
  void restoresTheOuterRequestIdInMdcRatherThanBlindlyClearingIt() throws Exception {
    MDC.put("requestId", "outer-scope-value");
    HttpServletRequest request = syncRequest("GET", "/api/v1/runs", null);
    HttpServletResponse response = mockResponse(200);

    filter.doFilter(request, response, (req, res) -> {});

    assertThat(MDC.get("requestId")).isEqualTo("outer-scope-value");
  }

  @Test
  void theAsyncCallbacksOwnLoggingRestoresWhateverRequestIdThatThreadAlreadyHadToo()
      throws Exception {
    HttpServletRequest request = asyncRequest("/api/v1/runs/run-1/events");
    AsyncContext asyncContext = mock(AsyncContext.class);
    when(request.getAsyncContext()).thenReturn(asyncContext);
    ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);
    HttpServletResponse response = mockResponse(200);

    filter.doFilter(request, response, (req, res) -> {});
    verify(asyncContext).addListener(listenerCaptor.capture());

    MDC.put("requestId", "some-other-threads-existing-value");
    listenerCaptor.getValue().onComplete(mock(AsyncEvent.class));

    assertThat(MDC.get("requestId")).isEqualTo("some-other-threads-existing-value");
  }

  private static HttpServletRequest asyncRequest(String uri) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getRequestURI()).thenReturn(uri);
    when(request.getHeader("X-Request-ID")).thenReturn(null);
    when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).thenReturn(uri);
    when(request.isAsyncStarted()).thenReturn(true);
    return request;
  }

  @Test
  void anExceptionFromTheChainStillPropagatesClearsMdcAndLogsExactlyOnceWithInferredStatus()
      throws Exception {
    HttpServletRequest request = syncRequest("POST", "/api/v1/runs", null);
    HttpServletResponse response = mockResponse(200);
    when(response.isCommitted()).thenReturn(false);
    RuntimeException boom = new RuntimeException("boom");
    FilterChain chain =
        (req, res) -> {
          throw boom;
        };

    assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isSameAs(boom);

    assertThat(logAppender.list).hasSize(1);
    Map<String, Object> pairs = keyValuePairs(logAppender.list.get(0));
    assertThat(pairs.get("status")).isEqualTo(500);
    assertThat(pairs.get("outcome")).isEqualTo("exception");
    assertThat(MDC.get("requestId")).isNull();
  }

  @Test
  void aSuccessfulHealthProbeLogsAtDebugWhileAFailingOneLogsAtInfo() throws Exception {
    // Live acceptance against a real running system found the real Spring Boot actuator
    // WebMvcEndpointHandlerMapping resolves BEST_MATCHING_PATTERN_ATTRIBUTE to the coarse
    // "/actuator/health/**" wildcard, never the literal "/actuator/health/readiness" - a first
    // draft of this test set both the request's URI and that attribute to the identical literal
    // path, which passed while the real suppression logic silently never fired against a real
    // server. Reproduced here by giving the two a genuinely different value, exactly like the
    // real mismatch.
    HttpServletRequest okRequest = healthProbeRequest("readiness", null);
    HttpServletResponse okResponse = mockResponse(200);
    filter.doFilter(okRequest, okResponse, (req, res) -> {});

    HttpServletRequest failingRequest = healthProbeRequest("readiness", null);
    HttpServletResponse failingResponse = mockResponse(503);
    filter.doFilter(failingRequest, failingResponse, (req, res) -> {});

    assertThat(logAppender.list).hasSize(2);
    assertThat(logAppender.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
    assertThat(logAppender.list.get(1).getLevel()).isEqualTo(Level.INFO);
  }

  private static HttpServletRequest healthProbeRequest(String probe, String requestIdHeader) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("GET");
    when(request.getRequestURI()).thenReturn("/actuator/health/" + probe);
    when(request.getHeader("X-Request-ID")).thenReturn(requestIdHeader);
    when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE))
        .thenReturn("/actuator/health/**");
    when(request.isAsyncStarted()).thenReturn(false);
    return request;
  }

  private static HttpServletRequest syncRequest(String method, String uri, String requestIdHeader) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn(method);
    when(request.getRequestURI()).thenReturn(uri);
    when(request.getHeader("X-Request-ID")).thenReturn(requestIdHeader);
    when(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)).thenReturn(uri);
    when(request.isAsyncStarted()).thenReturn(false);
    return request;
  }

  private static HttpServletResponse mockResponse(int status) {
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getStatus()).thenReturn(status);
    return response;
  }

  private static Map<String, Object> keyValuePairs(ILoggingEvent event) {
    Map<String, Object> result = new HashMap<>();
    if (event.getKeyValuePairs() != null) {
      for (var pair : event.getKeyValuePairs()) {
        result.put(pair.key, pair.value);
      }
    }
    return result;
  }
}
