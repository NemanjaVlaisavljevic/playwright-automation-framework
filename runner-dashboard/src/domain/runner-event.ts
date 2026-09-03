import { z } from "zod";

/**
 * Hand-written, not generated: `runner-service` deliberately excludes the SSE endpoint from its
 * OpenAPI document (see `docs/SSE_CONTRACT_V1.md` in the repo root - OpenAPI has no way to
 * describe a named-event `text/event-stream` payload without misrepresenting it), so this is the
 * one wire contract in the app with no generated counterpart to fall back on or cross-check
 * against. Keep it in sync with `runner-contract`'s `RunnerEvent` record by hand.
 */
export const RunOutcome = z.enum([
  "SUCCEEDED",
  "FAILED",
  "TIMED_OUT",
  "CANCELLED",
  "ERROR",
]);
export type RunOutcome = z.infer<typeof RunOutcome>;

const runLevelEventTypes = [
  "RUN_QUEUED",
  "RUN_STARTED",
  "RUN_FINISHED",
] as const;
const testLevelEventTypes = [
  "TEST_STARTED",
  "TEST_PASSED",
  "TEST_FAILED",
  "TEST_ABORTED",
  "TEST_SKIPPED",
] as const;
const stepLevelEventTypes = [
  "STEP_STARTED",
  "STEP_PASSED",
  "STEP_FAILED",
] as const;

export const EventType = z.enum([
  ...runLevelEventTypes,
  ...testLevelEventTypes,
  ...stepLevelEventTypes,
]);
export type EventType = z.infer<typeof EventType>;

/** Java's `String.isBlank()` rejects `""` and whitespace-only strings alike; `z.string()` alone accepts both. */
const nonBlankString = z.string().regex(/\S/, "must not be blank");

/**
 * A minimal shape used to check `runId`/`schemaVersion` before attempting full V1 validation - see
 * `run-event-reducer.ts`'s staged validation. Permissive about everything except those two fields:
 * a real future V2 event (a new `type`, a reshaped payload) must classify as an unsupported-schema-
 * version compatibility error, not a generic protocol error, which only works if the version is
 * checked against a shape this loose *about `type`*, before the strict V1 `RunnerEvent` union below
 * ever gets a chance to reject it for having the "wrong" shape. `schemaVersion`/`runId` still use
 * `nonBlankString`, not plain `z.string()`, though: a blank value is malformed, not a legitimate
 * (if unsupported) future version, and must fail as a protocol error right here - reaching the
 * `schemaVersion !== CURRENT_SCHEMA_VERSION` comparison with a blank value would misreport it as an
 * unsupported-but-otherwise-well-formed version instead.
 */
export const RunnerEventEnvelope = z.object({
  schemaVersion: nonBlankString,
  runId: nonBlankString,
});
export type RunnerEventEnvelope = z.infer<typeof RunnerEventEnvelope>;

/**
 * Every field the wire format's `NON_NULL` Jackson config can ever omit is `.optional()`, never
 * nullable - the backend never sends `null`, it omits the key entirely (see SSE_CONTRACT_V1.md).
 * `detail` is left optional on every variant uniformly (not restricted to the ones that normally
 * carry one) rather than tightened per event type: the backend record's own constructor doesn't
 * forbid it either, and a frontend contract stricter than the backend's own doesn't buy anything
 * here - a real but unexpected `detail` on, say, `TEST_STARTED` should still parse.
 *
 * Every variant is `.strict()`: `runner-contract`'s `RunnerEvent` compact constructor actively
 * rejects a run-level event carrying `testId`/`testDisplayName`, a non-terminal event carrying
 * `runOutcome`, etc. - the Java side is a closed, cross-scope-field-forbidding contract, so a
 * frontend schema built with plain `z.object()` (which silently strips unknown keys instead of
 * rejecting them) would accept and hide exactly the kind of malformed event the backend itself
 * guarantees can never happen; `.strict()` keeps that same invariant here instead of loosening it.
 */
const baseFields = {
  schemaVersion: nonBlankString,
  runId: nonBlankString,
  sequence: z.int().positive(),
  timestamp: z.iso.datetime(),
  detail: z.string().optional(),
};

const RunQueuedEvent = z
  .object({ ...baseFields, type: z.literal("RUN_QUEUED") })
  .strict();
const RunStartedEvent = z
  .object({ ...baseFields, type: z.literal("RUN_STARTED") })
  .strict();
const RunFinishedEvent = z
  .object({
    ...baseFields,
    type: z.literal("RUN_FINISHED"),
    runOutcome: RunOutcome,
  })
  .strict();

const testLevelFields = {
  ...baseFields,
  testId: nonBlankString,
  testDisplayName: nonBlankString,
};

const TestStartedEvent = z
  .object({ ...testLevelFields, type: z.literal("TEST_STARTED") })
  .strict();
const TestPassedEvent = z
  .object({ ...testLevelFields, type: z.literal("TEST_PASSED") })
  .strict();
const TestFailedEvent = z
  .object({ ...testLevelFields, type: z.literal("TEST_FAILED") })
  .strict();
const TestAbortedEvent = z
  .object({ ...testLevelFields, type: z.literal("TEST_ABORTED") })
  .strict();
const TestSkippedEvent = z
  .object({ ...testLevelFields, type: z.literal("TEST_SKIPPED") })
  .strict();

/**
 * Additive over the original schema 1.0 `RUN_*`/`TEST_*` vocabulary (see `CURRENT_SCHEMA_VERSION`
 * below): emitted by the main suite's `Steps` API from inside a running test method, never by the
 * JUnit listener itself. A test that never uses `Steps` emits none of these - a step-free test and
 * a step-using one coexist in the same run, but every event either of them produces still carries
 * the one current `schemaVersion`: schema versions themselves are never mixed in a single stream.
 */
const stepLevelFields = {
  ...testLevelFields,
  stepId: nonBlankString,
  stepName: nonBlankString,
};

const StepStartedEvent = z
  .object({ ...stepLevelFields, type: z.literal("STEP_STARTED") })
  .strict();
const StepPassedEvent = z
  .object({ ...stepLevelFields, type: z.literal("STEP_PASSED") })
  .strict();
const StepFailedEvent = z
  .object({ ...stepLevelFields, type: z.literal("STEP_FAILED") })
  .strict();

export const RunnerEvent = z.discriminatedUnion("type", [
  RunQueuedEvent,
  RunStartedEvent,
  RunFinishedEvent,
  TestStartedEvent,
  TestPassedEvent,
  TestFailedEvent,
  TestAbortedEvent,
  TestSkippedEvent,
  StepStartedEvent,
  StepPassedEvent,
  StepFailedEvent,
]);
export type RunnerEvent = z.infer<typeof RunnerEvent>;

export type RunLevelEventType = (typeof runLevelEventTypes)[number];
export type TestLevelEventType = (typeof testLevelEventTypes)[number];
export type StepLevelEventType = (typeof stepLevelEventTypes)[number];

export function isTestLevelEvent(
  event: RunnerEvent,
): event is Extract<RunnerEvent, { type: TestLevelEventType }> {
  return (testLevelEventTypes as readonly string[]).includes(event.type);
}

export function isStepLevelEvent(
  event: RunnerEvent,
): event is Extract<RunnerEvent, { type: StepLevelEventType }> {
  return (stepLevelEventTypes as readonly string[]).includes(event.type);
}

/** The schema version this frontend build was written against - see `RunnerEvent.CURRENT_SCHEMA_VERSION` in `runner-contract`. */
export const CURRENT_SCHEMA_VERSION = "1.1";
