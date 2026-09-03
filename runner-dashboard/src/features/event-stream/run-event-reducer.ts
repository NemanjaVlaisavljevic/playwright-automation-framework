import {
  CURRENT_SCHEMA_VERSION,
  isStepLevelEvent,
  isTestLevelEvent,
  RunnerEvent,
  RunnerEventEnvelope,
  type RunOutcome,
  type StepLevelEventType,
  type TestLevelEventType,
} from "../../domain/runner-event";

export type TestExecutionStatus =
  "RUNNING" | "PASSED" | "FAILED" | "ABORTED" | "SKIPPED";

export type StepExecutionStatus = "RUNNING" | "PASSED" | "FAILED";

export interface StepExecution {
  readonly stepId: string;
  readonly stepName: string;
  readonly status: StepExecutionStatus;
  /** The sequence of the first event seen for this step - for stable display ordering. */
  readonly firstSequence: number;
  readonly startedAt?: string;
  readonly finishedAt?: string;
  readonly detail?: string;
}

export interface TestExecution {
  readonly testId: string;
  readonly testDisplayName: string;
  readonly status: TestExecutionStatus;
  /** The sequence of the first event seen for this test - for stable display ordering. */
  readonly firstSequence: number;
  readonly startedAt?: string;
  readonly finishedAt?: string;
  readonly detail?: string;
  /**
   * Keyed by `stepId`, insertion-ordered by first appearance - empty for a test that never used the
   * `Steps` API. `STEP_*` is purely additive over the original `RUN_*`/`TEST_*` vocabulary (see
   * `docs/SSE_CONTRACT_V1.md`) - every event still carries the same `schemaVersion` regardless.
   */
  readonly steps: ReadonlyMap<string, StepExecution>;
}

/**
 * Once `status.kind` is anything but `"active"`, the stream is frozen: `applyRunnerEventMessage`
 * returns the same state unchanged for any further message. Recovering from any of these means the
 * caller reconnecting from scratch (a fresh `EventSource`, a fresh reducer state) - never patching
 * around it, per docs/SSE_CONTRACT_V1.md's own guidance for a client that ever observes a gap.
 */
export type RunEventStreamStatus =
  | { kind: "active" }
  | { kind: "gap"; expectedSequence: number; receivedSequence: number }
  | { kind: "protocol-error"; reason: string }
  | { kind: "compatibility-error"; receivedSchemaVersion: string }
  | { kind: "terminal"; runOutcome: RunOutcome };

/**
 * `eventsBySequence`/`testsById` are `ReadonlyMap`, not `Map`: a consuming component holding this
 * state (e.g. via `useReducer`) must never be able to call `.set(...)`/`.delete(...)` directly and
 * mutate React state out from under the reducer - every transition builds a fresh `Map` internally
 * (see `applyRunnerEventMessage`) and only ever hands the result out as read-only. This also
 * reinforces the invariant that every sequence up to `lastSequence` exists in `eventsBySequence`:
 * external code has no way to poke a hole in it.
 */
export interface RunEventStreamState {
  readonly status: RunEventStreamStatus;
  readonly eventsBySequence: ReadonlyMap<number, RunnerEvent>;
  readonly testsById: ReadonlyMap<string, TestExecution>;
  readonly lastSequence: number;
  readonly runOutcome?: RunOutcome;
  /**
   * Set once, from `RUN_STARTED`'s own `timestamp` - lets a caller (see `use-run-event-stream.ts`)
   * detect the run-started transition confirmed by the SSE lifecycle itself, to refresh the REST
   * `RunResponse` snapshot exactly once rather than leaving it stuck at whatever status the initial
   * `GET` happened to catch (`QUEUED`/`STARTING`) for the entire live run.
   */
  readonly runStartedAt?: string;
  /**
   * Set once, from `RUN_FINISHED`'s own `timestamp` - the primary terminal-time signal for
   * reconciling a test/step that never reported its own terminal result (see
   * `run-details-view-model.ts`). Preferring this over the REST `RunResponse.finishedAt` means
   * reconciliation does not depend on a REST refetch succeeding or being fresh: the stream already
   * knows the run is over, and knows exactly when, the instant this event is processed - no round
   * trip required, and nothing for a failed/stale refetch to strand.
   */
  readonly runFinishedAt?: string;
}

export function createInitialRunEventStreamState(): RunEventStreamState {
  return {
    status: { kind: "active" },
    eventsBySequence: new Map(),
    testsById: new Map(),
    lastSequence: 0,
  };
}

const statusByTestLevelEventType: Record<
  TestLevelEventType,
  TestExecutionStatus
> = {
  TEST_STARTED: "RUNNING",
  TEST_PASSED: "PASSED",
  TEST_FAILED: "FAILED",
  TEST_ABORTED: "ABORTED",
  TEST_SKIPPED: "SKIPPED",
};

const statusByStepLevelEventType: Record<
  StepLevelEventType,
  StepExecutionStatus
> = {
  STEP_STARTED: "RUNNING",
  STEP_PASSED: "PASSED",
  STEP_FAILED: "FAILED",
};

/**
 * Structural equality for two already-validated `RunnerEvent`s. `JSON.stringify` is safe here
 * specifically because both sides are Zod parse *output*: Zod always builds the parsed object by
 * iterating its own schema's key order, not the input JSON's, so two structurally identical events
 * produce identically-ordered objects even if their original wire JSON had keys in a different
 * order - this would not be a safe comparison for arbitrary/untrusted JSON.
 */
function runnerEventsAreEqual(a: RunnerEvent, b: RunnerEvent): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

/** `false` only ever means `"protocol-error"` upstream - see the two call sites below. */
type ApplyResult =
  | { readonly ok: true; readonly test: TestExecution }
  | { readonly ok: false; readonly reason: string };

function isTerminalTestStatus(status: TestExecutionStatus): boolean {
  return status !== "RUNNING";
}

/**
 * Rejects a semantically-impossible test-level lifecycle as a protocol error - the ingestor only
 * checks shape/runId/sequence (see `ListenerEventIngestor`), so a corrupted or hand-crafted stream
 * can otherwise reach this far looking "valid". The real system's own JUnit-driven lifecycle admits
 * exactly two shapes for a test (see `RunnerEventTestExecutionListener`): `TEST_STARTED` followed
 * by exactly one of `TEST_PASSED`/`TEST_FAILED`/`TEST_ABORTED`, or a lone `TEST_SKIPPED` with no
 * `TEST_STARTED` at all (JUnit Platform never calls `executionStarted` for a test it goes on to
 * report as skipped) - so:
 * - `TEST_STARTED` requires the testId not already be known (a repeat is never a legitimate reset).
 * - `TEST_PASSED`/`TEST_FAILED`/`TEST_ABORTED` require an existing, still-`RUNNING` test.
 * - `TEST_SKIPPED` requires the testId not already be known (same reasoning as `TEST_STARTED`).
 * - No event may carry a different `testDisplayName` than the one already known for that testId, or
 *   finish a test while one of its own steps is still `RUNNING` (impossible in the real system -
 *   `Steps.run` always completes a step, PASSED or FAILED, before returning).
 */
function applyTestEvent(
  existing: TestExecution | undefined,
  event: Extract<RunnerEvent, { type: TestLevelEventType }>,
): ApplyResult {
  if (event.type === "TEST_STARTED" || event.type === "TEST_SKIPPED") {
    if (existing !== undefined) {
      return {
        ok: false,
        reason: `${event.type} at sequence ${event.sequence} repeats already-known test "${event.testId}"`,
      };
    }
  } else if (existing === undefined) {
    return {
      ok: false,
      reason: `${event.type} at sequence ${event.sequence} references test "${event.testId}" that never received TEST_STARTED`,
    };
  } else if (isTerminalTestStatus(existing.status)) {
    return {
      ok: false,
      reason: `${event.type} at sequence ${event.sequence} arrived for test "${event.testId}" after it was already terminal (${existing.status})`,
    };
  }
  if (
    existing !== undefined &&
    existing.testDisplayName !== event.testDisplayName
  ) {
    return {
      ok: false,
      reason: `${event.type} at sequence ${event.sequence} changed testDisplayName for test "${event.testId}" from "${existing.testDisplayName}" to "${event.testDisplayName}"`,
    };
  }
  if (event.type !== "TEST_STARTED" && existing !== undefined) {
    const runningStep = Array.from(existing.steps.values()).find(
      (step) => step.status === "RUNNING",
    );
    if (runningStep !== undefined) {
      return {
        ok: false,
        reason: `${event.type} at sequence ${event.sequence} finished test "${event.testId}" while step "${runningStep.stepId}" ("${runningStep.stepName}") was still RUNNING`,
      };
    }
  }
  const detail = event.detail ?? existing?.detail;
  return {
    ok: true,
    test: {
      testId: event.testId,
      testDisplayName: event.testDisplayName,
      status: statusByTestLevelEventType[event.type],
      firstSequence: existing?.firstSequence ?? event.sequence,
      ...(event.type === "TEST_STARTED"
        ? { startedAt: event.timestamp }
        : existing?.startedAt !== undefined
          ? { startedAt: existing.startedAt }
          : {}),
      ...(event.type !== "TEST_STARTED" ? { finishedAt: event.timestamp } : {}),
      ...(detail !== undefined ? { detail } : {}),
      steps: existing?.steps ?? new Map(),
    },
  };
}

/**
 * Rejects a semantically-impossible step-level lifecycle as a protocol error, the step-scoped
 * counterpart to {@link applyTestEvent}'s own invariants: a `STEP_PASSED`/`STEP_FAILED` for a step
 * that never received `STEP_STARTED`, a repeated `STEP_STARTED` for an already-known step (never a
 * legitimate reset), a step whose `stepName` changes mid-stream, or any step-level event arriving
 * for a test that has already reached a terminal status.
 */
function applyStepEvent(
  existingTest: TestExecution,
  event: Extract<RunnerEvent, { type: StepLevelEventType }>,
): ApplyResult {
  if (isTerminalTestStatus(existingTest.status)) {
    return {
      ok: false,
      reason: `${event.type} at sequence ${event.sequence} arrived for test "${event.testId}" after it was already terminal (${existingTest.status})`,
    };
  }
  if (existingTest.testDisplayName !== event.testDisplayName) {
    return {
      ok: false,
      reason: `${event.type} at sequence ${event.sequence} carries testDisplayName "${event.testDisplayName}" but test "${event.testId}" is already known as "${existingTest.testDisplayName}"`,
    };
  }
  const existingStep = existingTest.steps.get(event.stepId);
  if (event.type === "STEP_STARTED") {
    if (existingStep !== undefined) {
      return {
        ok: false,
        reason: `STEP_STARTED at sequence ${event.sequence} repeats already-known step "${event.stepId}"`,
      };
    }
  } else if (existingStep === undefined) {
    return {
      ok: false,
      reason: `${event.type} at sequence ${event.sequence} references step "${event.stepId}" that never received STEP_STARTED`,
    };
  } else if (existingStep.status !== "RUNNING") {
    // A step is terminal (PASSED/FAILED) the instant its own event lands - `Steps.run` never
    // revisits a step once it has reported an outcome. Without this, a STEP_PASSED followed by a
    // STEP_FAILED for the same stepId would silently overwrite an already-terminal step's own
    // outcome instead of being rejected as the impossible transition it is.
    return {
      ok: false,
      reason: `${event.type} at sequence ${event.sequence} arrived for step "${event.stepId}" after it was already terminal (${existingStep.status})`,
    };
  } else if (existingStep.stepName !== event.stepName) {
    return {
      ok: false,
      reason: `${event.type} at sequence ${event.sequence} changed stepName for step "${event.stepId}" from "${existingStep.stepName}" to "${event.stepName}"`,
    };
  }
  const detail = event.detail ?? existingStep?.detail;
  const updatedStep: StepExecution = {
    stepId: event.stepId,
    stepName: event.stepName,
    status: statusByStepLevelEventType[event.type],
    firstSequence: existingStep?.firstSequence ?? event.sequence,
    ...(event.type === "STEP_STARTED"
      ? { startedAt: event.timestamp }
      : existingStep?.startedAt !== undefined
        ? { startedAt: existingStep.startedAt }
        : {}),
    ...(event.type !== "STEP_STARTED" ? { finishedAt: event.timestamp } : {}),
    ...(detail !== undefined ? { detail } : {}),
  };
  const steps = new Map(existingTest.steps);
  steps.set(event.stepId, updatedStep);
  return { ok: true, test: { ...existingTest, steps } };
}

/**
 * Applies one raw SSE `data:` payload (still a JSON string - not yet parsed) to `state` for the
 * run identified by `runId`, replay or live alike: this is deliberately the single code path for
 * both, since the wire contract makes no distinction between a replayed and a live event beyond
 * timing.
 *
 * Frozen once `state.status.kind !== "active"` (see {@link RunEventStreamStatus}) - returns `state`
 * unchanged rather than continuing to interpret events after a gap/protocol/compatibility error or
 * the stream's own terminal event.
 */
export function applyRunnerEventMessage(
  state: RunEventStreamState,
  runId: string,
  rawMessage: string,
): RunEventStreamState {
  if (state.status.kind !== "active") {
    return state;
  }

  let parsedJson: unknown;
  try {
    parsedJson = JSON.parse(rawMessage);
  } catch {
    return {
      ...state,
      status: { kind: "protocol-error", reason: "malformed JSON" },
    };
  }

  // Staged on purpose: a real future V2 event (a new `type`, a reshaped payload) must classify as
  // an unsupported-schema-version compatibility error, not a generic protocol error - which only
  // works if runId/schemaVersion are checked against the loose envelope *before* the strict V1
  // `RunnerEvent` union gets a chance to reject an unrecognized `type` as "doesn't match the
  // contract." Validating the full V1 shape first (as an earlier version of this function did)
  // meant every real V2 event failed at that step, before its version was ever inspected.
  const envelope = RunnerEventEnvelope.safeParse(parsedJson);
  if (!envelope.success) {
    return {
      ...state,
      status: {
        kind: "protocol-error",
        reason: "event does not have a schemaVersion/runId envelope",
      },
    };
  }

  if (envelope.data.runId !== runId) {
    return {
      ...state,
      status: {
        kind: "protocol-error",
        reason: `event runId "${envelope.data.runId}" does not match the expected runId "${runId}"`,
      },
    };
  }

  if (envelope.data.schemaVersion !== CURRENT_SCHEMA_VERSION) {
    return {
      ...state,
      status: {
        kind: "compatibility-error",
        receivedSchemaVersion: envelope.data.schemaVersion,
      },
    };
  }

  const result = RunnerEvent.safeParse(parsedJson);
  if (!result.success) {
    return {
      ...state,
      status: {
        kind: "protocol-error",
        reason: "event does not match the RunnerEvent contract",
      },
    };
  }
  const event = result.data;

  // Already seen (or older than) this sequence - normally a benign replay/reconnect overlap, but
  // only when the content actually matches what was already recorded at that sequence. A conflict
  // (same sequence, different type/timestamp/detail/...) means two different events are claiming
  // the same slot in the canonical journal, which the server-side contract guarantees can never
  // happen - so it's treated as a protocol violation, not silently smoothed over.
  if (event.sequence <= state.lastSequence) {
    const previouslySeen = state.eventsBySequence.get(event.sequence);
    if (
      previouslySeen !== undefined &&
      !runnerEventsAreEqual(previouslySeen, event)
    ) {
      return {
        ...state,
        status: {
          kind: "protocol-error",
          reason: `event at sequence ${event.sequence} conflicts with a previously seen event`,
        },
      };
    }
    return state;
  }

  if (event.sequence > state.lastSequence + 1) {
    return {
      ...state,
      status: {
        kind: "gap",
        expectedSequence: state.lastSequence + 1,
        receivedSequence: event.sequence,
      },
    };
  }

  const eventsBySequence = new Map(state.eventsBySequence);
  eventsBySequence.set(event.sequence, event);

  const testsById = new Map(state.testsById);
  if (isTestLevelEvent(event)) {
    const result = applyTestEvent(testsById.get(event.testId), event);
    if (!result.ok) {
      return {
        ...state,
        status: { kind: "protocol-error", reason: result.reason },
      };
    }
    testsById.set(event.testId, result.test);
  } else if (isStepLevelEvent(event)) {
    const existingTest = testsById.get(event.testId);
    if (existingTest === undefined) {
      return {
        ...state,
        status: {
          kind: "protocol-error",
          reason: `${event.type} at sequence ${event.sequence} references unknown testId "${event.testId}"`,
        },
      };
    }
    const result = applyStepEvent(existingTest, event);
    if (!result.ok) {
      return {
        ...state,
        status: { kind: "protocol-error", reason: result.reason },
      };
    }
    testsById.set(event.testId, result.test);
  }

  return {
    status:
      event.type === "RUN_FINISHED"
        ? { kind: "terminal", runOutcome: event.runOutcome }
        : state.status,
    eventsBySequence,
    testsById,
    lastSequence: event.sequence,
    ...(event.type === "RUN_FINISHED"
      ? { runOutcome: event.runOutcome, runFinishedAt: event.timestamp }
      : {}),
    ...(state.runStartedAt !== undefined
      ? { runStartedAt: state.runStartedAt }
      : event.type === "RUN_STARTED"
        ? { runStartedAt: event.timestamp }
        : {}),
  };
}
