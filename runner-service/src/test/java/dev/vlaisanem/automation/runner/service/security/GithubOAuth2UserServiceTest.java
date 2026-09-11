package dev.vlaisanem.automation.runner.service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Uses a fake delegate so no real HTTP call is made - only the numeric-id-allowlist mapping is
 * exercised. See {@link SecurityAccessMatrixTest} for authorization-rule behavior once a principal
 * already exists.
 */
class GithubOAuth2UserServiceTest {

  private static OAuth2User githubUserWith(Object id) {
    Map<String, Object> attributes = new HashMap<>();
    if (id != null) {
      attributes.put("id", id);
    }
    attributes.put("login", "octocat");
    attributes.put("avatar_url", "https://example.invalid/avatar.png");
    return new DefaultOAuth2User(
        java.util.Set.of(
            new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER")),
        attributes,
        id == null ? "login" : "id");
  }

  @SuppressWarnings("unchecked")
  private static OAuth2UserService<OAuth2UserRequest, OAuth2User> delegateReturning(
      OAuth2User user) {
    OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = mock(OAuth2UserService.class);
    when(delegate.loadUser(org.mockito.ArgumentMatchers.any())).thenReturn(user);
    return delegate;
  }

  @Test
  void grantsRoleAdminForAMatchingNumericId() {
    GithubOAuth2UserService service =
        new GithubOAuth2UserService(
            AdminGithubAllowlist.parse("123456"), delegateReturning(githubUserWith(123456)));

    OAuth2User result = service.loadUser(mock(OAuth2UserRequest.class));

    assertThat(result.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_ADMIN");
  }

  @Test
  void matchesAStringNumericIdTheSameAsAnIntegerOne() {
    GithubOAuth2UserService service =
        new GithubOAuth2UserService(
            AdminGithubAllowlist.parse("123456"), delegateReturning(githubUserWith("123456")));

    OAuth2User result = service.loadUser(mock(OAuth2UserRequest.class));

    assertThat(result.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_ADMIN");
  }

  @Test
  void rejectsANonMatchingId() {
    GithubOAuth2UserService service =
        new GithubOAuth2UserService(
            AdminGithubAllowlist.parse("123456"), delegateReturning(githubUserWith(999999)));

    assertThatThrownBy(() -> service.loadUser(mock(OAuth2UserRequest.class)))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessageContaining("not an administrator");
  }

  @Test
  void rejectsAMissingIdAttribute() {
    GithubOAuth2UserService service =
        new GithubOAuth2UserService(
            AdminGithubAllowlist.parse("123456"), delegateReturning(githubUserWith(null)));

    assertThatThrownBy(() -> service.loadUser(mock(OAuth2UserRequest.class)))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessageContaining("valid numeric id");
  }

  @Test
  void rejectsANonNumericIdAttribute() {
    GithubOAuth2UserService service =
        new GithubOAuth2UserService(
            AdminGithubAllowlist.parse("123456"),
            delegateReturning(githubUserWith("not-a-number")));

    assertThatThrownBy(() -> service.loadUser(mock(OAuth2UserRequest.class)))
        .isInstanceOf(OAuth2AuthenticationException.class)
        .hasMessageContaining("valid numeric id");
  }
}
