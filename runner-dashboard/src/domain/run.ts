import type { CreateRunRequest, RunResponse } from "../api/runner-api";

/**
 * Derived from the generated types (`CreateRunRequest["environment"]`/`["suite"]`,
 * `RunResponse["status"]`) rather than hand-typed unions, so a real contract change (a new suite,
 * a new environment, a new status) shows up here automatically the next time `npm run
 * api:generate` runs, instead of silently drifting out of sync with a hand-maintained copy.
 * Imported from `api/runner-api.ts`, not `api/generated/` directly - see that file's re-export
 * comment for why.
 */
export type Environment = CreateRunRequest["environment"];
export type Suite = CreateRunRequest["suite"];
export type RunStatus = RunResponse["status"];

const TERMINAL_STATUSES: ReadonlySet<RunStatus> = new Set([
  "SUCCEEDED",
  "FAILED",
  "CANCELLED",
  "TIMED_OUT",
  "ERROR",
]);

/** Mirrors `RunStatus.isTerminal()` on the backend (`runner-service`'s domain model). */
export function isTerminalRunStatus(status: RunStatus): boolean {
  return TERMINAL_STATUSES.has(status);
}
