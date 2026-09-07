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
 * Caps the request body size (D3.3) - registered early in both {@link SecurityConfig} chains,
 * unlike {@link AbuseRateLimitFilter}: this is a resource-protection mechanism, not an auth-
 * adjacent one, so there is no reason it should differ between local dev and production. Applies to
 * every request, not just {@code POST /api/v1/runs} - the one endpoint with a real JSON body today,
 * but a future mutating endpoint gets this protection for free.
 *
 * <p><strong>The whole body is read and bounded-checked here, before {@code filterChain.doFilter}
 * is ever called - never a passive stream wrapper hoping a downstream read failure surfaces
 * usefully (a review finding).</strong> An earlier version wrapped the input stream to throw {@code
 * IOException} once the cap was exceeded mid-read, then let {@code filterChain.doFilter} proceed -
 * but by the time such an exception surfaces from inside Jackson's own read (deep inside {@code
 * DispatcherServlet}'s handler invocation), Spring MVC's own exception resolution has already
 * caught and resolved it internally (typically to a plain {@code 400}, via {@code
 * HttpMessageNotReadableException}) and committed a response - it never propagates back up far
 * enough for this filter's own {@code try/catch} around {@code doFilter} to ever see it. That made
 * the {@code 413} contract non-uniform: a declared over-cap {@code Content-Length} got a clean
 * {@code 413}, but a chunked or falsified-{@code Content-Length} body - the more important case,
 * since it is exactly what an attacker controls - never reliably did.
 *
 * <p>Reading the whole body up front and checking it before ever invoking the rest of the chain
 * closes that gap entirely: {@code Content-Length} is still rejected immediately when it already
 * declares an over-cap size (avoiding even attempting to read a body that will only be discarded),
 * but a request with no reliable {@code Content-Length} at all is bounded the same way, by actual
 * bytes read, and a genuine {@code 413} is written directly by this filter in both cases - {@code
 * filterChain.doFilter} (and therefore any deserialization/controller code) is never reached at all
 * for an oversized body, checked either way. Buffering the whole body in memory is safe at this
 * cap's scale (16 KiB by default - a real {@code CreateRunRequest} payload is well under it) and
 * for a `GET` request with no body at all, the very first read immediately returns end-of-stream,
 * so this adds no meaningful overhead there (including for a long-lived SSE `GET`).
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
