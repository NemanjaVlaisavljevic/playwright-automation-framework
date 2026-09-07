package dev.vlaisanem.automation.runner.service.api;

/**
 * {@code canManageRuns} is the one field the frontend must actually gate the Run/Cancel controls on
 * - never {@code authenticated} alone. The two are deliberately not the same thing: when GitHub
 * OAuth2 is not configured at all (the permissive chain - default local {@code bootRun}, {@code
 * dashboardE2eTest}), nobody is ever "authenticated" as anyone, but every caller can still manage
 * runs, exactly like this project's whole pre-D3.2 history. Only once OAuth2 is genuinely enabled
 * does managing runs require being the allowlisted admin. {@code authenticationRequired} tells the
 * frontend whether logging in is even a concept in this deployment - the login link/control is
 * hidden entirely when it is {@code false}, since offering a login UI with no backing OAuth2 login
 * endpoint would be a dead link.
 *
 * <p>{@code login}/{@code avatarUrl} are simply omitted when {@code authenticated} is {@code false}
 * - relies on {@code application.yml}'s existing global {@code
 * spring.jackson.default-property-inclusion: non_null}, no extra Jackson config needed here.
 */
public record CurrentUserResponse(
    boolean authenticationRequired,
    boolean canManageRuns,
    boolean authenticated,
    String login,
    String avatarUrl) {

  /** GitHub OAuth2 is not configured at all (the permissive chain) - see the class doc above. */
  public static CurrentUserResponse permissive() {
    return new CurrentUserResponse(false, true, false, null, null);
  }

  /** GitHub OAuth2 is enabled, but this caller has not logged in. */
  public static CurrentUserResponse anonymous() {
    return new CurrentUserResponse(true, false, false, null, null);
  }

  /**
   * A real, logged-in GitHub identity without {@code ROLE_ADMIN}. Today this never actually happens
   * - {@code GithubOAuth2UserService} rejects any non-allowlisted GitHub account before a session
   * is ever established, so the only authenticated principal that can exist is the admin - but
   * {@link CurrentUserController} derives this from the real {@code ROLE_ADMIN} authority rather
   * than assuming every {@code OAuth2User} principal is the admin, so this response stays correct
   * even if that invariant ever changes.
   */
  public static CurrentUserResponse authenticatedNonAdmin(String login, String avatarUrl) {
    return new CurrentUserResponse(true, false, true, login, avatarUrl);
  }

  /** The one and only authenticated-with-permission outcome once GitHub OAuth2 is enabled. */
  public static CurrentUserResponse authenticatedAdmin(String login, String avatarUrl) {
    return new CurrentUserResponse(true, true, true, login, avatarUrl);
  }
}
