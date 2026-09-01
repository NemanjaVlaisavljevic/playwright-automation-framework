import {
  CURRENT_SCHEMA_VERSION,
  isTestLevelEvent,
  RunnerEvent,
  RunnerEventEnvelope,
  type RunOutcome,
  type TestLevelEventType,
} from "../../domain/runner-event";

export type TestExecutionStatus =
  "RUNNING" | "PASSED" | "FAILED" | "ABORTED" | "SKIPPED";

export interface TestExecution {
  readonly testId: string;
  readonly testDisplayName: string;
  readonly status: TestExecutionStatus;
  /** The sequence of the first event seen for this test - for stable display ordering. */
  readonly firstSequence: number;
  readonly startedAt?: string;
  readonly finishedAt?: string;
  readonly detail?: string;
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

function applyTestEvent(
  existing: TestExecution | undefined,
  event: Extract<RunnerEvent, { type: TestLevelEventType }>,
): TestExecution {
  const detail = event.detail ?? existing?.detail;
  return {
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
  };
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
    testsById.set(
      event.testId,
      applyTestEvent(testsById.get(event.testId), event),
    );
  }

  return {
    status:
      event.type === "RUN_FINISHED"
        ? { kind: "terminal", runOutcome: event.runOutcome }
        : state.status,
    eventsBySequence,
    testsById,
    lastSequence: event.sequence,
    ...(event.type === "RUN_FINISHED" ? { runOutcome: event.runOutcome } : {}),
    ...(state.runStartedAt !== undefined
      ? { runStartedAt: state.runStartedAt }
      : event.type === "RUN_STARTED"
        ? { runStartedAt: event.timestamp }
        : {}),
  };
}
