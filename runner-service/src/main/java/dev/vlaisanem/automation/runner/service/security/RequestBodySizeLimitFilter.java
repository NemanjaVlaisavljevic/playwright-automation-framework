package dev.vlaisanem.automation.runner.service.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the request body size - registered early in both {@link SecurityConfig} chains, unlike
 * {@link AbuseRateLimitFilter}: this is resource protection, not auth-adjacent, so it should not
 * differ between local dev and production. Applies to every request, not just {@code POST
 * /api/v1/runs}, so a future mutating endpoint gets this protection for free.
 *
 * <p>The whole body is read and bounded-checked here, before {@code filterChain.doFilter} is ever
 * called, rather than via a stream wrapper that throws mid-read: an exception thrown that deep
 * (inside Jackson, inside {@code DispatcherServlet}) gets caught and resolved by Spring MVC's own
 * exception handling before it ever reaches this filter's {@code try/catch}, so a chunked or
 * falsified-{@code Content-Length} body - exactly what an attacker controls - would never reliably
 * get a clean {@code 413}. Reading up front closes that gap for both cases, and {@code
 * filterChain.doFilter} is never reached at all for an oversized body. Buffering in memory is safe
 * at this cap's scale (16 KiB by default), and a bodyless {@code GET} (including long-lived SSE)
 * sees immediate end-of-stream, so this adds no meaningful overhead there.
 */
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

  private final long maxRequestBodyBytes;
  private final ObjectMapper objectMapper;

  public RequestBodySizeLimitFilter(RunnerProperties properties, ObjectMapper objectMapper) {
    this.maxRequestBodyBytes = properties.maxRequestBodyBytes();
    this.objectMapper = objectMapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    long declaredLength = request.getContentLengthLong();
    if (declaredLength > maxRequestBodyBytes) {
      writeTooLarge(request, response);
      return;
    }
    byte[] buffered;
    try {
      buffered = readBounded(request.getInputStream(), maxRequestBodyBytes);
    } catch (RequestBodyTooLargeException oversized) {
      writeTooLarge(request, response);
      return;
    }
    filterChain.doFilter(new BufferedBodyRequestWrapper(request, buffered), response);
  }

  /**
   * Reads the entire stream into memory, throwing {@link RequestBodyTooLargeException} the moment
   * more than {@code limit} bytes have actually been delivered - independent of whatever {@code
   * Content-Length} the request declared (or omitted, for a chunked body).
   */
  private static byte[] readBounded(InputStream input, long limit) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    long total = 0;
    int read;
    while ((read = input.read(chunk)) != -1) {
      total += read;
      if (total > limit) {
        throw new RequestBodyTooLargeException();
      }
      buffer.write(chunk, 0, read);
    }
    return buffer.toByteArray();
  }

  private void writeTooLarge(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.CONTENT_TOO_LARGE,
            "Request body exceeds the maximum allowed size of " + maxRequestBodyBytes + " bytes.");
    problem.setInstance(URI.create(request.getRequestURI()));
    response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(response.getWriter(), problem);
  }

  /** Internal signal only - never escapes this filter, always turned into a {@code 413} here. */
  private static final class RequestBodyTooLargeException extends IOException {}

  /** Re-serves the already-buffered, already-size-checked body to everything downstream. */
  private static final class BufferedBodyRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] body;

    private BufferedBodyRequestWrapper(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream source = new ByteArrayInputStream(body);
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
        public void setReadListener(ReadListener readListener) {
          // Never asynchronous - the whole body is already buffered by the time this wrapper
          // exists, so there is nothing to notify a ReadListener about.
        }
      };
    }
  }
}
