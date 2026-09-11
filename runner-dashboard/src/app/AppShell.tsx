import { useQuery } from "@tanstack/react-query";
import { NavLink, Outlet } from "react-router-dom";
import { queryKeys } from "../api/query-keys";
import { RunnerApiError } from "../api/problem-detail";
import { getHealth } from "../api/runner-api";
import { cx } from "../components/ui/cx";
import { AuthControls } from "../features/auth/AuthControls";
import styles from "./AppShell.module.css";

export interface AppShellProps {
  /** Overridable for tests - see AppShell.test.tsx's recovery test. */
  healthRefetchIntervalMs?: number;
}

/**
 * App-wide chrome (header/sidebar/health indicator) wrapping every route via a React Router
 * layout route (see `router.tsx`), so the health indicator is visible on every page.
 */
export function AppShell({
  healthRefetchIntervalMs = 10_000,
}: AppShellProps = {}) {
  const health = useQuery({
    queryKey: queryKeys.health,
    queryFn: getHealth,
    refetchInterval: healthRefetchIntervalMs,
    // Without this, TanStack Query pauses polling while the tab is unfocused - defeating recovery
    // detection for a dashboard left open in the background.
    refetchIntervalInBackground: true,
  });

  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <span className={styles.brand}>Runner Dashboard</span>
        <div role="status" className={styles.health}>
          {health.isPending && (
            <span className={cx(styles.healthPill, styles.healthPending)}>
              Checking runner service…
            </span>
          )}
          {health.isError && (
            <span className={cx(styles.healthPill, styles.healthDown)}>
              Runner service unavailable: {describeError(health.error)}
            </span>
          )}
          {health.isSuccess && (
            <span className={cx(styles.healthPill, styles.healthUp)}>
              Runner service: {health.data.status}
            </span>
          )}
        </div>
        <AuthControls />
      </header>
      <div className={styles.body}>
        <nav className={styles.sidebar} aria-label="Primary">
          <NavLink
            to="/runs"
            className={({ isActive }) =>
              isActive
                ? `${styles.navLink} ${styles.navLinkActive}`
                : styles.navLink
            }
          >
            Runs
          </NavLink>
          <NavLink
            to="/performance"
            className={({ isActive }) =>
              isActive
                ? `${styles.navLink} ${styles.navLinkActive}`
                : styles.navLink
            }
          >
            Performance
          </NavLink>
        </nav>
        <main className={styles.content}>
          <Outlet />
        </main>
      </div>
    </div>
  );
}

function describeError(error: unknown): string {
  return error instanceof RunnerApiError ? error.message : "Unknown error";
}
