package dev.vlaisanem.automation.runner.service.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Spring Security's default {@code AuthenticationEntryPoint} redirects an unauthenticated caller to
 * an HTML login page - it fires inside the security filter chain, before {@code DispatcherServlet}
 * dispatch, so it never goes through {@code RunExceptionHandler}'s controller advice. This backend
 * serves no browser-rendered pages of its own (the SPA is served separately by Caddy), so an HTML
 * redirect is never correct here - this writes the same {@link ProblemDetail} shape every other
 * error in this API already uses, {@code instance} included (matching `RunExceptionHandler`'s own
 * contract - see {@code OpenApiConfig}'s {@code problemDetailContractCustomizer}), using the
 * application's own managed {@link ObjectMapper} so this response serializes identically to every
 * other one in the API.
 */
@Component
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  public ProblemDetailAuthenticationEntryPoint(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED, "Authentication is required to perform this action.");
    problem.setInstance(URI.create(request.getRequestURI()));
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(response.getWriter(), problem);
  }
}
