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
  /** Keyed by `stepId`, insertion-ordered; empty for a test that never used the `Steps` API. */
  readonly steps: ReadonlyMap<string, StepExecution>;
}

/**
 * Once `status.kind` isn't `"active"` the stream is frozen: `applyRunnerEventMessage` returns state
 * unchanged for further messages. Recovery means reconnecting from scratch, never patching around it.
 */
export type RunEventStreamStatus =
  | { kind: "active" }
  | { kind: "gap"; expectedSequence: number; receivedSequence: number }
  | { kind: "protocol-error"; reason: string }
  | { kind: "compatibility-error"; receivedSchemaVersion: string }
  | { kind: "terminal"; runOutcome: RunOutcome };

/**
 * `eventsBySequence`/`testsById` are `ReadonlyMap`, not `Map`, so a consumer can never mutate React
 * state out from under the reducer - every transition builds a fresh `Map` internally.
 */
export interface RunEventStreamState {
  readonly status: RunEventStreamStatus;
  readonly eventsBySequence: ReadonlyMap<number, RunnerEvent>;
  readonly testsById: ReadonlyMap<string, TestExecution>;
  readonly lastSequence: number;
  readonly runOutcome?: RunOutcome;
  /** Set once from `RUN_STARTED`'s timestamp; lets a caller refresh the REST snapshot exactly once. */
  readonly runStartedAt?: string;
  /**
   * Set once from `RUN_FINISHED`'s timestamp - the terminal-time signal for reconciling a
   * test/step with no terminal result, without depending on a REST refetch.
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
 * Structural equality for two already-validated `RunnerEvent`s. `JSON.stringify` is safe here only
 * because both sides are Zod parse output with deterministic key order - not safe for raw JSON.
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
 * Rejects a semantically-impossible test-level lifecycle as a protocol error (the ingestor only
 * checks shape/runId/sequence). Valid shapes: `TEST_STARTED` then exactly one of
 * PASSED/FAILED/ABORTED, or a lone `TEST_SKIPPED`. A testId can't repeat `STARTED`/`SKIPPED`,
 * `testDisplayName` can't change mid-stream, and a test can't finish while a step is still RUNNING.
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
 * Step-level counterpart to {@link applyTestEvent}: rejects a `STEP_PASSED`/`FAILED` with no prior
 * `STEP_STARTED`, a repeated `STEP_STARTED`, a changed `stepName`, or a step event for an
 * already-terminal test.
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
    // A step is terminal the instant its event lands; a second outcome for the same stepId is rejected.
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
 * Applies one raw SSE `data:` payload to `state` for `runId` - the single code path for both
 * replay and live events. Frozen once `state.status.kind !== "active"`: returns state unchanged.
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

  // Check runId/schemaVersion against the loose envelope before the strict V1 union, so a future
  // V2 event classifies as a compatibility error rather than a generic protocol error.
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

  // Already seen (or older than) this sequence - a benign replay/reconnect overlap only if the
  // content matches what was recorded; a mismatch at the same sequence is a protocol violation.
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
