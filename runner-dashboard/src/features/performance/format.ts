import type { BadgeStatus } from "../../components/ui/StatusBadge";

/** Maps a metric's `passed` value to a badge status - `null` means no threshold, not a failure. */
export function resultStatus(passed: boolean | null): BadgeStatus {
  if (passed === true) {
    return "PASSED";
  }
  if (passed === false) {
    return "REGRESSION";
  }
  return "OBSERVED ONLY";
}

/** Sub-second latency values, always shown to one decimal place - "5.0 ms", never a bare "5". */
export function formatMs(valueMs: number): string {
  return `${valueMs.toFixed(1)} ms`;
}

/** `p95LimitMs === null` means no locked threshold; renders "—" rather than a misleading "0%". */
export function formatUtilization(
  p95Ms: number,
  p95LimitMs: number | null,
): string {
  if (p95LimitMs === null) {
    return "—";
  }
  return `${Math.round((p95Ms / p95LimitMs) * 100)}%`;
}
