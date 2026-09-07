package dev.vlaisanem.automation.runner.service.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Companion to {@link ProblemDetailAuthenticationEntryPoint} for the authenticated-but-forbidden
 * case - same rationale (bypasses {@code RunExceptionHandler}, must never render Spring Security's
 * default HTML error page, uses the application's own managed {@link ObjectMapper} and sets {@code
 * instance}), same {@link ProblemDetail} shape.
 */
@Component
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

  private final ObjectMapper objectMapper;

  public ProblemDetailAccessDeniedHandler(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
      throws IOException {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN,
            "You are authenticated, but do not have permission to perform this action.");
    problem.setInstance(URI.create(request.getRequestURI()));
    response.setStatus(HttpStatus.FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(response.getWriter(), problem);
  }
}
