import { useQuery } from "@tanstack/react-query";
import { getCurrentUser } from "../../api/auth-api";
import { queryKeys } from "../../api/query-keys";

/** Mirrors `AppShell`'s existing health-check query pattern. */
export function useCurrentUser() {
  return useQuery({
    queryKey: queryKeys.currentUser,
    queryFn: getCurrentUser,
  });
}
