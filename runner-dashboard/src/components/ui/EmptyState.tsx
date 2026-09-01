import type { ReactNode } from "react";
import styles from "./EmptyState.module.css";

export interface EmptyStateProps {
  title: string;
  children?: ReactNode;
}

/** For a genuinely empty collection (no runs yet, no matches for a filter) - not for loading or
 * error states, which have their own dedicated treatment (`LoadingSkeleton`, `Alert`). */
export function EmptyState({ title, children }: EmptyStateProps) {
  return (
    <div className={styles.emptyState}>
      <p className={styles.title}>{title}</p>
      {children !== undefined && <p className={styles.detail}>{children}</p>}
    </div>
  );
}
