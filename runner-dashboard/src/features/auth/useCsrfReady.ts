import { useQuery } from "@tanstack/react-query";
import { primeCsrfToken } from "../../api/auth-api";
import { queryKeys } from "../../api/query-keys";

export interface UseCsrfReadyOptions {
  /** Overridable for tests - see AuthControls.test.tsx's recovery test. */
  retryIntervalMs?: number;
}

/**
 * Wraps `primeCsrfToken` in a query so a priming failure retries on its own instead of leaving
 * mutating controls permanently unable to send a CSRF header. Shared across all callers via
 * `queryKeys.csrf`, so only one priming request is ever in flight.
 */
export function useCsrfReady({
  retryIntervalMs = 5_000,
}: UseCsrfReadyOptions = {}) {
  return useQuery({
    queryKey: queryKeys.csrf,
    // queryFn must resolve to a real value: TanStack treats `undefined` as an error, and
    // primeCsrfToken() itself returns Promise<void>.
    queryFn: async () => {
      await primeCsrfToken();
      return true;
    },
    refetchInterval: (query) =>
      query.state.status === "error" ? retryIntervalMs : false,
    refetchIntervalInBackground: true,
  });
}
