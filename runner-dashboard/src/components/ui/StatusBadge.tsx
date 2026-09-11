import { cx } from "./cx";
import styles from "./StatusBadge.module.css";

/** Every status either a `RunResponse` or a `TestExecution` can carry - one badge for both. */
export type BadgeStatus =
  | "QUEUED"
  | "STARTING"
  | "RUNNING"
  | "SUCCEEDED"
  | "PASSED"
  | "FAILED"
  | "ERROR"
  | "CANCELLED"
  | "ABORTED"
  | "TIMED_OUT"
  | "SKIPPED"
  // Display-only relabel by `run-details-view-model.ts` for a test/step still RUNNING when the
  // run itself already reached a terminal status - never a real wire-level status.
  | "INTERRUPTED"
  // A `PerformanceBaseline` scenario status (domain/performance-baseline.ts), not a run/test status.
  | "REGRESSION"
  // A `PerformanceBaseline` metric with no locked threshold (`passed: null`) - reported, not judged.
  | "OBSERVED ONLY";

type Tone = "neutral" | "info" | "success" | "danger" | "warning";

const TONE_BY_STATUS: Record<BadgeStatus, Tone> = {
  QUEUED: "neutral",
  STARTING: "info",
  RUNNING: "info",
  SUCCEEDED: "success",
  PASSED: "success",
  FAILED: "danger",
  ERROR: "danger",
  CANCELLED: "neutral",
  ABORTED: "warning",
  TIMED_OUT: "warning",
  SKIPPED: "neutral",
  INTERRUPTED: "warning",
  REGRESSION: "danger",
  "OBSERVED ONLY": "neutral",
};

export interface StatusBadgeProps {
  status: BadgeStatus;
}

/**
 * The status name itself is always the badge's text - color is purely reinforcement, never the
 * only signal (a color-blind user, or a screen reader, gets the same information either way).
 */
export function StatusBadge({ status }: StatusBadgeProps) {
  return (
    <span className={cx(styles.badge, styles[TONE_BY_STATUS[status]])}>
      {status}
    </span>
  );
}
