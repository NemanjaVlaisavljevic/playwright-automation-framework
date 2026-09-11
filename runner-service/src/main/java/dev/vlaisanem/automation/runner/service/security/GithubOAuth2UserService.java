package dev.vlaisanem.automation.runner.service.security;

import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * Resolves a GitHub login to either {@code ROLE_ADMIN} or a failed authentication - there is no
 * intermediate "authenticated but not admin" outcome. A non-allowlisted account never receives any
 * {@code SecurityContext}/session at all: Spring Security treats an exception thrown from an {@link
 * OAuth2UserService} as an authentication failure, before any session is established.
 *
 * <p>Compares only GitHub's immutable numeric {@code "id"} attribute against the injected {@link
 * AdminGithubAllowlist} - never {@code "login"} (the username, which can change). A missing or
 * non-numeric {@code "id"}, or a malformed GitHub response, is always treated as a failed login.
 */
@Component
@ConditionalOnProperty(name = "runner.security.oauth2-enabled", havingValue = "true")
public class GithubOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

  private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate;
  private final AdminGithubAllowlist allowlist;

  @Autowired
  public GithubOAuth2UserService(AdminGithubAllowlist allowlist) {
    this(allowlist, new DefaultOAuth2UserService());
  }

  /** Test-only seam: a real {@link DefaultOAuth2UserService} makes an actual HTTP call. */
  GithubOAuth2UserService(
      AdminGithubAllowlist allowlist, OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate) {
    this.allowlist = allowlist;
    this.delegate = delegate;
  }

  @Override
  public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
    OAuth2User user = delegate.loadUser(userRequest);

    Object rawId = user.getAttribute("id");
    long githubId;
    try {
      githubId =
          switch (rawId) {
            case null -> throw new NumberFormatException("id attribute is missing");
            case Number number -> number.longValue();
            default -> Long.parseLong(rawId.toString().trim());
          };
    } catch (NumberFormatException notNumeric) {
      // The 2-arg (OAuth2Error, Throwable) constructor sets getMessage() to cause.getMessage(),
      // not the error's description - the 3-arg form is what actually surfaces this message.
      String message = "GitHub user response did not contain a valid numeric id";
      throw new OAuth2AuthenticationException(
          new OAuth2Error("invalid_user_info", message, null), message, notNumeric);
    }

    if (!allowlist.contains(githubId)) {
      throw new OAuth2AuthenticationException(
          new OAuth2Error("access_denied", "This GitHub account is not an administrator", null));
    }

    return new DefaultOAuth2User(
        Set.of(new SimpleGrantedAuthority("ROLE_ADMIN")), user.getAttributes(), "id");
  }
}
