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
 * Explicitly loads (or generates and saves) the CSRF token via the same {@link CsrfTokenRepository}
 * the security chain uses, which makes {@code CookieCsrfTokenRepository} write/refresh the {@code
 * XSRF-TOKEN} cookie as a response side-effect - Spring Security's own documented pattern for a
 * SPA, since a plain {@code GET} otherwise never touches the token at all. The cookie is the actual
 * transport; this endpoint's body carries nothing.
 *
 * <p>Deliberately explicit (load-or-generate-and-save) rather than relying on the filter chain's
 * own deferred-token resolution: confirmed empirically that merely resolving a {@code CsrfToken}
 * controller-method argument does not by itself force the underlying repository save/cookie write
 * in this Spring Security version.
 *
 * <p>The token is session-bound and Spring Security rotates the session ID on successful
 * authentication, so a token obtained before login/logout is invalid afterward - the frontend calls
 * this once on app bootstrap (naturally re-primed after a post-login full-page reload) and again
 * explicitly after logout succeeds (which does not reload the page).
 *
 * <p>Kept out of the OpenAPI document, same rationale as {@link CurrentUserController}.
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
