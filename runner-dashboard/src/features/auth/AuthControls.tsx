import { useMutation, useQueryClient } from "@tanstack/react-query";
import { loginHref, logout } from "../../api/auth-api";
import { queryKeys } from "../../api/query-keys";
import { Button } from "../../components/ui/Button";
import { useCsrfReady } from "./useCsrfReady";
import { useCurrentUser } from "./useCurrentUser";
import styles from "./AuthControls.module.css";

/**
 * Header widget: pending/error -> nothing; GitHub OAuth2 not configured at all (the permissive
 * chain - `authenticationRequired: false`) -> nothing, since there is no login concept to offer in
 * this mode (a link with no backing OAuth2 endpoint would be a dead link); authentication required
 * but not logged in -> "Log in with GitHub" link; logged in (always the allowlisted admin - see
 * `GithubOAuth2UserService`, there is no other authenticated state) -> avatar + login name +
 * Logout button.
 */
export function AuthControls() {
  const currentUser = useCurrentUser();
  const queryClient = useQueryClient();

  // App-bootstrap CSRF priming, with automatic recovery if the backend is unreachable at mount -
  // see useCsrfReady's own doc comment. No separate post-login refresh is needed (a successful
  // login's full-page redirect re-triggers this same bootstrap query for the new session).
  useCsrfReady();

  const logoutMutation = useMutation({
    mutationFn: logout,
    onSuccess: async () => {
      // Invalidates the shared query, rather than calling primeCsrfToken() directly, so every
      // consumer of it (this component's own useCsrfReady() call above, and useCanManageRuns() in
      // any currently-mounted admin-gated control) observes the re-primed token for the new
      // (post-logout, anonymous) session - not just whichever component happened to trigger it.
      await queryClient.invalidateQueries({ queryKey: queryKeys.csrf });
      await queryClient.invalidateQueries({ queryKey: queryKeys.currentUser });
    },
  });

  if (!currentUser.isSuccess || !currentUser.data.authenticationRequired) {
    return null;
  }

  if (!currentUser.data.authenticated) {
    return (
      <a className={styles.loginLink} href={loginHref()}>
        Log in with GitHub
      </a>
    );
  }

  const { login, avatarUrl } = currentUser.data;
  return (
    <div className={styles.controls}>
      {avatarUrl !== undefined && (
        <img
          className={styles.avatar}
          src={avatarUrl}
          alt=""
          aria-hidden="true"
        />
      )}
      <span className={styles.login}>{login}</span>
      <Button
        size="compact"
        onClick={() => logoutMutation.mutate()}
        disabled={logoutMutation.isPending}
      >
        Log out
      </Button>
    </div>
  );
}
