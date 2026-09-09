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
  // Display-only - a test/step relabeled by `run-details-view-model.ts` because it was still
  // RUNNING when the run itself already reached a terminal status. Never a real wire-level status.
  | "INTERRUPTED"
  // D4.4.3c - a `PerformanceBaseline` scenario's own status (domain/performance-baseline.ts) - never
  // a wire-level run/test status either.
  | "REGRESSION"
  // D4.4.3c - a `PerformanceBaseline` metric with no locked threshold at all (`passed: null` -
  // `LatencyMetric.p95LimitMs`/`SignalMetric` genuinely un-gated, see scenario-configs.mjs's own
  // `thresholdRequired: false`) - reported, never judged pass/fail.
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
