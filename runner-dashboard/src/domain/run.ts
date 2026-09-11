import type { CreateRunRequest, RunResponse } from "../api/runner-api";

/**
 * Derived from the generated types rather than hand-typed unions, so a contract change stays in
 * sync automatically. Imported via `api/runner-api.ts`, not `api/generated/` directly.
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
