import { z } from "zod";

/**
 * Hand-written, not generated: the SSE endpoint is excluded from the OpenAPI document (see
 * `docs/SSE_CONTRACT_V1.md`). Keep in sync with `runner-contract`'s `RunnerEvent` record by hand.
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
 * Minimal shape used to check `runId`/`schemaVersion` before full V1 validation (see
 * `run-event-reducer.ts`). Loose about everything else so a future V2 event's unknown shape is
 * classified as an unsupported-schema-version error, not a generic protocol error.
 */
export const RunnerEventEnvelope = z.object({
  schemaVersion: nonBlankString,
  runId: nonBlankString,
});
export type RunnerEventEnvelope = z.infer<typeof RunnerEventEnvelope>;

/**
 * Optional fields are `.optional()`, never nullable - the backend omits the key rather than
 * sending `null`. `.strict()` mirrors the backend's cross-scope-field rejection (e.g. a run-level
 * event can't carry `testId`).
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
 * Additive over schema 1.0's `RUN_*`/`TEST_*` vocabulary; emitted by the `Steps` API from inside a
 * test method, never by the JUnit listener. A step-free test simply emits none of these.
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
