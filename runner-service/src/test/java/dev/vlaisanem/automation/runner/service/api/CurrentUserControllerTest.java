package dev.vlaisanem.automation.runner.service.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vlaisanem.automation.runner.service.config.RunnerSecurityProperties;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

/**
 * Plain unit tests, no Spring context - {@link CurrentUserController#currentUser} is a pure
 * function of {@link RunnerSecurityProperties} and the current {@code Authentication}, and
 * exercising all three real-world outcomes this way is far simpler than wiring a full
 * {@code @WebMvcTest} slice for each. Regression tests for the review finding that conflating
 * "authenticated" with "can manage runs" made a permissive-chain deployment (no GitHub OAuth2
 * configured - default local {@code bootRun}, {@code dashboardE2eTest}) silently read-only: {@code
 * canManageRuns} must stay {@code true} there even though nobody is ever "authenticated" as anyone.
 */
class CurrentUserControllerTest {

  @Test
  void permissiveChainAlwaysReportsCanManageRunsWithNoLoginConcept() {
    CurrentUserController controller =
        new CurrentUserController(new RunnerSecurityProperties(false, ""));

    CurrentUserResponse response = controller.currentUser(null);

    assertThat(response.authenticationRequired()).isFalse();
    assertThat(response.canManageRuns()).isTrue();
    assertThat(response.authenticated()).isFalse();
  }

  @Test
  void oauth2EnabledAnonymousCannotManageRunsAndMustLogIn() {
    CurrentUserController controller =
        new CurrentUserController(new RunnerSecurityProperties(true, "123456"));

    CurrentUserResponse response = controller.currentUser(null);

    assertThat(response.authenticationRequired()).isTrue();
    assertThat(response.canManageRuns()).isFalse();
    assertThat(response.authenticated()).isFalse();
  }

  /**
   * Regression test for the review finding: {@code canManageRuns} must come from the real {@code
   * ROLE_ADMIN} authority, not merely from the principal being an {@link
   * org.springframework.security.oauth2.core.user.OAuth2User} - a logged-in GitHub identity with no
   * {@code ROLE_ADMIN} authority (never actually produced by {@code GithubOAuth2UserService} today,
   * but exactly the kind of drift this explicit check guards against) must be reported as
   * authenticated without permission, not silently treated as the admin.
   */
  @Test
  void oauth2EnabledAuthenticatedNonAdminCannotManageRuns() {
    CurrentUserController controller =
        new CurrentUserController(new RunnerSecurityProperties(true, "123456"));
    var oauth2User =
        new DefaultOAuth2User(
            Set.of(new SimpleGrantedAuthority("ROLE_USER")),
            Map.of(
                "id", 999999,
                "login", "someone-else",
                "avatar_url", "https://example.invalid/other-avatar.png"),
            "id");
    // The 3-arg (principal, credentials, authorities) constructor is required, not the 2-arg
    // form: TestingAuthenticationToken's 2-arg constructor discards the principal's own
    // authorities entirely (authorities become an empty collection) and defaults isAuthenticated
    // to false, unlike a real post-OAuth2-login SecurityContext where both the ROLE_USER/
    // ROLE_ADMIN authority and isAuthenticated=true come from the completed login itself.
    var authentication =
        new TestingAuthenticationToken(oauth2User, null, oauth2User.getAuthorities());

    CurrentUserResponse response = controller.currentUser(authentication);

    assertThat(response.authenticationRequired()).isTrue();
    assertThat(response.canManageRuns()).isFalse();
    assertThat(response.authenticated()).isTrue();
    assertThat(response.login()).isEqualTo("someone-else");
  }

  @Test
  void oauth2EnabledAuthenticatedAdminCanManageRuns() {
    CurrentUserController controller =
        new CurrentUserController(new RunnerSecurityProperties(true, "123456"));
    var oauth2User =
        new DefaultOAuth2User(
            Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")),
            Map.of(
                "id", 123456,
                "login", "octocat",
                "avatar_url", "https://example.invalid/avatar.png"),
            "id");
    var authentication =
        new TestingAuthenticationToken(oauth2User, null, oauth2User.getAuthorities());

    CurrentUserResponse response = controller.currentUser(authentication);

    assertThat(response.authenticationRequired()).isTrue();
    assertThat(response.canManageRuns()).isTrue();
    assertThat(response.authenticated()).isTrue();
    assertThat(response.login()).isEqualTo("octocat");
    assertThat(response.avatarUrl()).isEqualTo("https://example.invalid/avatar.png");
  }
}
