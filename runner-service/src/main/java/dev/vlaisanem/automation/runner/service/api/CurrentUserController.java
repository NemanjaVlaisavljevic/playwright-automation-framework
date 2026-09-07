package dev.vlaisanem.automation.runner.service.api;

import dev.vlaisanem.automation.runner.service.config.RunnerSecurityProperties;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports the current session's effective permission to the frontend - never a token, and never
 * conflating "who is logged in" with "can this caller manage runs" (see {@link
 * CurrentUserResponse}'s own doc comment for why those differ when GitHub OAuth2 is not
 * configured). Kept hand-maintained on the frontend side rather than added to the OpenAPI document
 * (see the {@link Hidden} annotation below), the same treatment as the existing {@code
 * /actuator/health} endpoint: a simple, stable, rarely-changing shape not worth {@code npm run
 * api:check:contract} churn.
 */
@Hidden
@RestController
public class CurrentUserController {

  private static final String ROLE_ADMIN = "ROLE_ADMIN";

  private final RunnerSecurityProperties properties;

  public CurrentUserController(RunnerSecurityProperties properties) {
    this.properties = properties;
  }

  /**
   * Derives {@code canManageRuns} from the real {@code ROLE_ADMIN} authority Spring Security itself
   * grants (and the same authority {@code POST /api/v1/runs}/{@code cancel} require) - never
   * inferred merely from the principal being an {@link OAuth2User} (a review finding: that indirect
   * invariant - only relying on {@code GithubOAuth2UserService} never creating a non-admin session
   * - could silently drift from the actual authorization rule this endpoint is supposed to
   * describe).
   */
  @GetMapping("/api/v1/auth/me")
  public CurrentUserResponse currentUser(Authentication authentication) {
    if (!properties.oauth2Enabled()) {
      return CurrentUserResponse.permissive();
    }
    boolean isRealLogin =
        authentication != null
            && authentication.isAuthenticated()
            && authentication.getPrincipal() instanceof OAuth2User;
    if (!isRealLogin) {
      return CurrentUserResponse.anonymous();
    }
    OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
    Object login = oauth2User.getAttribute("login");
    Object avatarUrl = oauth2User.getAttribute("avatar_url");
    String loginValue = login == null ? null : login.toString();
    String avatarUrlValue = avatarUrl == null ? null : avatarUrl.toString();
    boolean isAdmin =
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(ROLE_ADMIN::equals);
    return isAdmin
        ? CurrentUserResponse.authenticatedAdmin(loginValue, avatarUrlValue)
        : CurrentUserResponse.authenticatedNonAdmin(loginValue, avatarUrlValue);
  }
}
