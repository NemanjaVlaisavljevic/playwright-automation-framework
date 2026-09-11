package dev.vlaisanem.automation.runner.service.api;

/**
 * {@code canManageRuns} is the field the frontend must gate Run/Cancel controls on, never {@code
 * authenticated} alone: when GitHub OAuth2 isn't configured, nobody is "authenticated" but every
 * caller can still manage runs. {@code authenticationRequired} hides the login control entirely
 * when this deployment has no OAuth2 login endpoint to point it at.
 */
public record CurrentUserResponse(
    boolean authenticationRequired,
    boolean canManageRuns,
    boolean authenticated,
    String login,
    String avatarUrl) {

  /** GitHub OAuth2 is not configured at all (the permissive chain). */
  public static CurrentUserResponse permissive() {
    return new CurrentUserResponse(false, true, false, null, null);
  }

  /** GitHub OAuth2 is enabled, but this caller has not logged in. */
  public static CurrentUserResponse anonymous() {
    return new CurrentUserResponse(true, false, false, null, null);
  }

  /**
   * A real, logged-in GitHub identity without {@code ROLE_ADMIN}. Never actually happens today
   * ({@code GithubOAuth2UserService} rejects non-allowlisted accounts before a session exists), but
   * kept distinct in case that invariant ever changes.
   */
  public static CurrentUserResponse authenticatedNonAdmin(String login, String avatarUrl) {
    return new CurrentUserResponse(true, false, true, login, avatarUrl);
  }

  /** The one and only authenticated-with-permission outcome once GitHub OAuth2 is enabled. */
  public static CurrentUserResponse authenticatedAdmin(String login, String avatarUrl) {
    return new CurrentUserResponse(true, true, true, login, avatarUrl);
  }
}
