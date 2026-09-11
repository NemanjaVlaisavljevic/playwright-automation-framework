import type { ConnectionState } from "../event-stream/use-run-event-stream";
import type { DisplayTest } from "./run-details-view-model";

/** A deep-link target within one run. A step is always addressed together with its owning test since `stepId` alone isn't unique run-wide. */
export type RunResultTarget =
  | { readonly kind: "test"; readonly testId: string }
  | { readonly kind: "step"; readonly testId: string; readonly stepId: string };

/**
 * `"none"` when no deep link was attempted; `"invalid"` when one was attempted but malformed (kept
 * distinct from `"none"` so a broken link shows an error rather than silently behaving like no link).
 */
export type ParsedRunResultTarget =
  | { readonly kind: "none" }
  | { readonly kind: "invalid" }
  | { readonly kind: "valid"; readonly target: RunResultTarget };

/** Parses `?testId=...` / `?testId=...&stepId=...` from a run details URL - see
 * {@link ParsedRunResultTarget}. */
export function parseRunResultTarget(
  searchParams: URLSearchParams,
): ParsedRunResultTarget {
  if (!searchParams.has("testId") && !searchParams.has("stepId")) {
    return { kind: "none" };
  }
  const testId = searchParams.get("testId");
  if (testId === null || testId.trim() === "") {
    return { kind: "invalid" };
  }
  const stepId = searchParams.get("stepId");
  if (stepId === null) {
    return { kind: "valid", target: { kind: "test", testId } };
  }
  if (stepId.trim() === "") {
    return { kind: "invalid" };
  }
  return { kind: "valid", target: { kind: "step", testId, stepId } };
}

/**
 * Builds the absolute, shareable URL for a deep link (always the full origin, since a relative
 * path would be meaningless pasted elsewhere). Query parameters, not path segments: a JUnit unique
 * id can contain `/`, `[`, `]`, `:`, and spaces, which `URLSearchParams` encodes correctly.
 */
export function buildRunResultUrl(
  runId: string,
  target: RunResultTarget,
): string {
  const params = new URLSearchParams({ testId: target.testId });
  if (target.kind === "step") {
    params.set("stepId", target.stepId);
  }
  return `${window.location.origin}/runs/${encodeURIComponent(runId)}?${params.toString()}`;
}

/** A stable string key for a target, since a freshly-parsed `RunResultTarget` is a new object every render. */
export function runResultTargetKey(target: RunResultTarget): string {
  return target.kind === "test"
    ? `test:${target.testId}`
    : `step:${target.testId}:${target.stepId}`;
}

export type DeepLinkStatus =
  | { readonly kind: "none" }
  | { readonly kind: "invalid" }
  | { readonly kind: "waiting" }
  | { readonly kind: "found" }
  | { readonly kind: "test-not-found" }
  | { readonly kind: "step-not-found" }
  | { readonly kind: "unavailable" };

/**
 * Resolves a target against the current test list without declaring it missing prematurely:
 * `RECOVERING` has wiped the test list for a fresh replay, so it reports "waiting" not "not found".
 * `PROTOCOL_ERROR` means live data is unavailable, a distinct case from "never existed", reported
 * as `"unavailable"`. A target is only "not found" once the stream itself reaches `CLOSED` - not
 * gated on a REST-derived terminal status, since REST can resolve before SSE replay finishes.
 */
export function computeDeepLinkStatus(
  target: RunResultTarget | undefined,
  tests: readonly DisplayTest[],
  connectionState: ConnectionState,
): DeepLinkStatus {
  if (target === undefined) {
    return { kind: "none" };
  }
  const test = tests.find((candidate) => candidate.testId === target.testId);
  if (test !== undefined) {
    if (target.kind === "test") {
      return { kind: "found" };
    }
    const step = test.steps.find(
      (candidate) => candidate.stepId === target.stepId,
    );
    if (step !== undefined) {
      return { kind: "found" };
    }
  }
  if (connectionState === "RECOVERING") {
    return { kind: "waiting" };
  }
  if (connectionState === "PROTOCOL_ERROR") {
    return { kind: "unavailable" };
  }
  if (connectionState === "CLOSED") {
    return test === undefined
      ? { kind: "test-not-found" }
      : { kind: "step-not-found" };
  }
  return { kind: "waiting" };
}

/** `undefined` for `"none"`/`"found"` - neither has anything to show the viewer. */
export function describeDeepLinkStatus(
  status: DeepLinkStatus,
): string | undefined {
  switch (status.kind) {
    case "none":
    case "found":
      return undefined;
    case "invalid":
      return "This result link is invalid.";
    case "waiting":
      return "Waiting for linked test result…";
    case "test-not-found":
      return "The linked test was not found in this run.";
    case "step-not-found":
      return "The linked step was not found in this test.";
    case "unavailable":
      return "The linked result could not be resolved because live event data is unavailable.";
  }
}
