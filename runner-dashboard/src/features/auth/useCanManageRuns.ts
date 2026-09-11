import { useCsrfReady, type UseCsrfReadyOptions } from "./useCsrfReady";
import { useCurrentUser } from "./useCurrentUser";

export interface UseCanManageRunsOptions {
  /** Forwarded to useCsrfReady - overridable for tests, see RunLaunchForm.test.tsx. */
  csrfRetryIntervalMs?: UseCsrfReadyOptions["retryIntervalMs"];
}

/**
 * True only when the backend grants manage-runs permission AND a CSRF token is ready to send -
 * an admin without a primed token would still get a 403 on mutating requests.
 */
export function useCanManageRuns({
  csrfRetryIntervalMs,
}: UseCanManageRunsOptions = {}): boolean {
  const currentUser = useCurrentUser();
  const csrf = useCsrfReady(
    csrfRetryIntervalMs !== undefined
      ? { retryIntervalMs: csrfRetryIntervalMs }
      : {},
  );
  return (
    currentUser.isSuccess && currentUser.data.canManageRuns && csrf.isSuccess
  );
}
