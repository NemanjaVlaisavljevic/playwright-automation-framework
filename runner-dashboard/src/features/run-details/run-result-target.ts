import type { ConnectionState } from "../event-stream/use-run-event-stream";
import type { DisplayTest } from "./run-details-view-model";

/**
 * A deep-link target within one run - query parameters, never path segments (see
 * `buildRunResultUrl`'s own doc comment for why). A step is only ever addressed together with its
 * owning test, since `stepId` alone is not unique run-wide (see `RunnerEvent`'s own contract - two
 * different tests may legitimately reuse the same `stepId`).
 */
export type RunResultTarget =
  | { readonly kind: "test"; readonly testId: string }
  | { readonly kind: "step"; readonly testId: string; readonly stepId: string };

/**
 * A parsed URL: `"none"` when there was no attempt at a deep link at all (neither `testId` nor
 * `stepId` present) - the ordinary case for a plain `/runs/:id` URL - versus `"invalid"` when a
 * deep link was clearly *attempted* but is malformed (a blank `testId`, a `stepId` with no
 * `testId`, or a blank `stepId`). Collapsing both into a bare `undefined`, as an earlier version of
 * this function did, meant a broken link silently behaved exactly like no link at all - a real
 * review finding, since the viewer then gets no indication anything was wrong with the URL they
 * followed.
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
 * Builds the absolute, shareable URL for a deep link - always the full origin, per the C4.5 spec's
 * own "kopira se apsolutni URL" requirement (a copied relative path would be meaningless pasted
 * anywhere but this same tab). Query parameters, not path segments: a real JUnit unique id routinely
 * contains `/`, `[`, `]`, `:`, parentheses, and spaces (see `ArtifactController`'s own equivalent
 * reasoning for artifact ids) - `URLSearchParams` handles that encoding correctly where a raw path
 * segment could not.
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

/** A stable string key for a target - used to recognize "this exact target was already handled"
 * across renders/effects (see `TestResultsSection.tsx` and `RunDetailsPage.tsx`), without relying
 * on object identity (a freshly-parsed `RunResultTarget` is a new object every render). */
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
 * Resolves a parsed `RunResultTarget` against the current (full, unfiltered) test list - the one
 * place that decides whether a deep link is still loading, has been found, or can no longer be
 * found. Never declares a target missing prematurely:
 * - `RECOVERING` has wiped the reducer's own test list for a fresh replay - a target genuinely
 *   already known before the gap must not flash "not found" while it rebuilds.
 * - `PROTOCOL_ERROR` (including a permanent second gap - see `use-run-event-stream.ts`) means the
 *   live event data itself is unavailable, a distinct case from "the run finished and the target
 *   never existed" - reported as its own `"unavailable"` status, not folded into either not-found
 *   case or a REST-derived 404.
 * - Otherwise, only once the *stream itself* is `CLOSED` (the reducer processed the replayed-or-live
 *   `RUN_FINISHED` event - see `use-run-event-stream.ts`) is a missing test or step ever reported as
 *   not found. Deliberately not gated on a REST-derived "is the run terminal" boolean instead: on a
 *   fresh deep-link load, `GET /runs/:id` routinely resolves *before* the SSE replay has delivered
 *   every event, so an already-terminal REST snapshot (a finished run opened well after the fact)
 *   would otherwise report "not found" for a target that is only a few more replayed events away -
 *   a real review finding. `CLOSED` inherently waits for the full replay, live or historical, since
 *   the reducer only reaches its own terminal state by actually processing `RUN_FINISHED`.
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
