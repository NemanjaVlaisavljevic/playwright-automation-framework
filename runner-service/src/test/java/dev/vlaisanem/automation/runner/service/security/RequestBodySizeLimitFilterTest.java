package dev.vlaisanem.automation.runner.service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.service.config.RateLimitRule;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * A plain unit test (no Spring context), driving the real {@link jakarta.servlet.Filter#doFilter}
 * directly with a hand-built request - proves the byte-counting read path itself, independent of
 * whatever {@code Content-Length} a request claims. {@code MockHttpServletRequest} (used by {@code
 * SecurityAccessMatrixTest}'s own MockMvc-based {@code 413} test) cannot simulate this specific
 * case: its {@code getContentLengthLong()} is always derived directly from the actual content bytes
 * it was given, so a real mismatch between declared and actual size - exactly what a chunked or
 * falsified request looks like - cannot be constructed through it at all.
 */
class RequestBodySizeLimitFilterTest {

  private static RunnerProperties properties(long maxRequestBodyBytes) {
    RateLimitRule aRule = new RateLimitRule(5, Duration.ofMinutes(1));
    return new RunnerProperties(
        ".",
        Duration.ofMinutes(10),
        "build/runner-events/raw",
        "build/runner-logs",
        "src/test/resources/catalog/public-test-catalog.json",
        "build/runner-artifacts",
        1024 * 1024,
        Duration.ofSeconds(5),
        Duration.ofSeconds(2),
        5,
        Duration.ofMillis(150),
        Duration.ofSeconds(5),
        100,
        Duration.ofSeconds(15),
        Duration.ofMinutes(10),
        aRule,
        aRule,
        aRule,
        aRule,
        aRule,
        aRule,
        aRule,
        3,
        maxRequestBodyBytes,
        Duration.ofDays(30),
        500,
        Duration.ofDays(14),
        Duration.ofHours(1),
        aRule);
  }

  /** A real {@code ObjectMapper}, matching what {@code JacksonConfig} actually provides. */
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void rejectsWithoutReliableContentLengthOnceActualBytesExceedTheCap() throws Exception {
    long cap = 1024;
    RequestBodySizeLimitFilter filter =
        new RequestBodySizeLimitFilter(properties(cap), OBJECT_MAPPER);

    byte[] actualBody = "x".repeat((int) cap + 1).getBytes();
    HttpServletRequest request = mock(HttpServletRequest.class);
    // The declared length is absent/unreliable - exactly a chunked-transfer-encoded request -
    // while the real stream still delivers more than the cap.
    when(request.getContentLengthLong()).thenReturn(-1L);
    when(request.getInputStream()).thenReturn(fakeServletInputStream(actualBody));
    when(request.getRequestURI()).thenReturn("/api/v1/runs");

    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();
    when(response.getWriter()).thenReturn(new PrintWriter(body));

    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    verify(response).setStatus(413);
    verify(chain, never()).doFilter(any(), any());
    assertThat(body.toString()).contains("\"status\":413");
  }

  @Test
  void passesThroughUnchangedWhenActualBytesAreUnderTheCapDespiteNoDeclaredLength()
      throws Exception {
    long cap = 1024;
    RequestBodySizeLimitFilter filter =
        new RequestBodySizeLimitFilter(properties(cap), OBJECT_MAPPER);

    byte[] actualBody = "short body".getBytes();
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getContentLengthLong()).thenReturn(-1L);
    when(request.getInputStream()).thenReturn(fakeServletInputStream(actualBody));

    HttpServletResponse response = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(any(), any());
    verify(response, never()).setStatus(413);
  }

  private static ServletInputStream fakeServletInputStream(byte[] content) {
    ByteArrayInputStream source = new ByteArrayInputStream(content);
    return new ServletInputStream() {
      @Override
      public int read() {
        return source.read();
      }

      @Override
      public int read(byte[] b, int off, int len) {
        return source.read(b, off, len);
      }

      @Override
      public boolean isFinished() {
        return source.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener readListener) {}
    };
  }
}
