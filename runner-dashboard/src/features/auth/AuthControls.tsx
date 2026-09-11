import { useMutation, useQueryClient } from "@tanstack/react-query";
import { loginHref, logout } from "../../api/auth-api";
import { queryKeys } from "../../api/query-keys";
import { Button } from "../../components/ui/Button";
import { useCsrfReady } from "./useCsrfReady";
import { useCurrentUser } from "./useCurrentUser";
import styles from "./AuthControls.module.css";

/**
 * Header widget: renders nothing when auth isn't required or state is pending/error, a login
 * link when unauthenticated, or avatar + name + logout when authenticated.
 */
export function AuthControls() {
  const currentUser = useCurrentUser();
  const queryClient = useQueryClient();

  useCsrfReady();

  const logoutMutation = useMutation({
    mutationFn: logout,
    onSuccess: async () => {
      // Invalidate rather than re-prime directly, so every mounted consumer of the shared
      // CSRF query picks up the new anonymous session's token.
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
