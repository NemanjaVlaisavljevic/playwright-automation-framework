import type { BadgeStatus } from "../../components/ui/StatusBadge";

/**
 * A per-metric `passed` value (`domain/performance-baseline.ts`'s own `LatencyMetric`/
 * `SignalMetric`) is never rendered as a bare true/false/null - it always goes through this, so a
 * genuinely un-gated metric ("OBSERVED ONLY") is never confused with one that was checked and
 * passed. A review finding: a scenario-level `StatusBadge` alone tells a viewer *that* something
 * regressed, never *which* metric - this is what makes that visible per-row, in both the latency
 * and signal tables.
 */
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

/**
 * `p95LimitMs === null` means this metric genuinely has no locked threshold (see
 * `domain/performance-baseline.ts`'s own `LatencyMetric` - never a missing/omitted value) - renders
 * as "—", the same "never coerce null" convention this project already uses elsewhere, rather than
 * a misleading "0%" or an empty cell that could be mistaken for a loading state.
 */
export function formatUtilization(
  p95Ms: number,
  p95LimitMs: number | null,
): string {
  if (p95LimitMs === null) {
    return "—";
  }
  return `${Math.round((p95Ms / p95LimitMs) * 100)}%`;
}
