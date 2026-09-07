import { useQuery } from "@tanstack/react-query";
import { primeCsrfToken } from "../../api/auth-api";
import { queryKeys } from "../../api/query-keys";

export interface UseCsrfReadyOptions {
  /** Overridable for tests - see AuthControls.test.tsx's recovery test. */
  retryIntervalMs?: number;
}

/**
 * Wraps `primeCsrfToken` in a query, rather than a bare fire-and-forget call, so a priming failure
 * (the backend unreachable at app bootstrap) recovers on its own instead of leaving every mutating
 * control permanently unable to send a valid CSRF header - mirrors RunLaunchForm's own
 * capabilities-retry pattern (error-only `refetchInterval`, `refetchIntervalInBackground: true` so
 * a tab left open, unfocused, across a backend restart still recovers without regaining focus
 * first). Shares one cached result across every consumer (`AuthControls`'s own bootstrap priming,
 * `useCanManageRuns` below) via the same `queryKeys.csrf` key, so only one `GET
 * /api/v1/auth/csrf` call is ever in flight at a time regardless of how many components call this.
 */
export function useCsrfReady({
  retryIntervalMs = 5_000,
}: UseCsrfReadyOptions = {}) {
  return useQuery({
    queryKey: queryKeys.csrf,
    // TanStack Query treats a queryFn resolving to `undefined` as an error ("Query data cannot be
    // undefined") - primeCsrfToken() itself returns Promise<void>, so this must resolve to a real
    // value on success, not just await it directly.
    queryFn: async () => {
      await primeCsrfToken();
      return true;
    },
    refetchInterval: (query) =>
      query.state.status === "error" ? retryIntervalMs : false,
    refetchIntervalInBackground: true,
  });
}
