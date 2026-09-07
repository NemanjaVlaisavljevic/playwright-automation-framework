import { useCsrfReady, type UseCsrfReadyOptions } from "./useCsrfReady";
import { useCurrentUser } from "./useCurrentUser";

export interface UseCanManageRunsOptions {
  /** Forwarded to useCsrfReady - overridable for tests, see RunLaunchForm.test.tsx. */
  csrfRetryIntervalMs?: UseCsrfReadyOptions["retryIntervalMs"];
}

/**
 * The one thing every admin-gated control (`RunLaunchForm`, `RunsTable`, `RunDetailsPage`) must
 * check - never `useCurrentUser().data.canManageRuns` alone. A logged-in admin whose CSRF token
 * hasn't primed yet (or failed to, and hasn't recovered) would still send a launch/cancel request
 * without a valid `X-XSRF-TOKEN` header, which the backend rejects with 403 regardless of the
 * caller's real permission - so "can manage runs" from the frontend's own perspective means both
 * "the backend says so" and "a CSRF token is actually ready to send."
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
