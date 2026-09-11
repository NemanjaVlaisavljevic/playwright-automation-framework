package dev.vlaisanem.automation.runner.service.api;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Explicitly loads-or-generates-and-saves the CSRF token so {@code CookieCsrfTokenRepository}
 * writes the {@code XSRF-TOKEN} cookie as a side effect; merely resolving a {@code CsrfToken}
 * controller argument does not by itself force that save in this Spring Security version.
 * Session-bound, so the frontend re-primes it on bootstrap and after logout. Hidden from OpenAPI
 * like {@link CurrentUserController}.
 */
@Hidden
@RestController
public class CsrfController {

  private final CsrfTokenRepository csrfTokenRepository;

  public CsrfController(CsrfTokenRepository csrfTokenRepository) {
    this.csrfTokenRepository = csrfTokenRepository;
  }

  @GetMapping("/api/v1/auth/csrf")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void primeCsrfToken(HttpServletRequest request, HttpServletResponse response) {
    CsrfToken token = csrfTokenRepository.loadToken(request);
    if (token == null) {
      token = csrfTokenRepository.generateToken(request);
    }
    csrfTokenRepository.saveToken(token, request, response);
  }
}
