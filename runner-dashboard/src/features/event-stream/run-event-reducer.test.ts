import { describe, expect, it } from "vitest";
import { CURRENT_SCHEMA_VERSION } from "../../domain/runner-event";
import {
  applyRunnerEventMessage,
  createInitialRunEventStreamState,
  type RunEventStreamState,
} from "./run-event-reducer";

const RUN_ID = "run-1";

function event(overrides: Record<string, unknown>): string {
  return JSON.stringify({
    schemaVersion: CURRENT_SCHEMA_VERSION,
    runId: RUN_ID,
    timestamp: "2026-08-31T20:28:52Z",
    ...overrides,
  });
}

function apply(state: RunEventStreamState, raw: string): RunEventStreamState {
  return applyRunnerEventMessage(state, RUN_ID, raw);
}

describe("applyRunnerEventMessage", () => {
  it("accepts a live sequence of run + test events, building the per-test view", () => {
    let state = createInitialRunEventStreamState();
    state = apply(state, event({ sequence: 1, type: "RUN_QUEUED" }));
    state = apply(state, event({ sequence: 2, type: "RUN_STARTED" }));
    state = apply(
      state,
      event({
        sequence: 3,
        type: "TEST_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );
    state = apply(
      state,
      event({
        sequence: 4,
        type: "TEST_PASSED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );

    expect(state.status).toEqual({ kind: "active" });
    expect(state.lastSequence).toBe(4);
    expect(state.eventsBySequence.size).toBe(4);
    expect(state.testsById.get("test-a")).toMatchObject({
      status: "PASSED",
      firstSequence: 3,
    });
  });

  it("builds nested step state under the owning test as STEP_* events stream in (schema 1.1)", () => {
    let state = createInitialRunEventStreamState();
    state = apply(
      state,
      event({
        sequence: 1,
        type: "TEST_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );
    state = apply(
      state,
      event({
        sequence: 2,
        type: "STEP_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
        stepId: "step-1",
        stepName: "open homepage",
      }),
    );
    state = apply(
      state,
      event({
        sequence: 3,
        type: "STEP_PASSED",
        testId: "test-a",
        testDisplayName: "a()",
        stepId: "step-1",
        stepName: "open homepage",
      }),
    );
    state = apply(
      state,
      event({
        sequence: 4,
        type: "STEP_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
        stepId: "step-2",
        stepName: "assert confirmation banner",
      }),
    );
    state = apply(
      state,
      event({
        sequence: 5,
        type: "STEP_FAILED",
        testId: "test-a",
        testDisplayName: "a()",
        stepId: "step-2",
        stepName: "assert confirmation banner",
        detail: "boom",
      }),
    );
    state = apply(
      state,
      event({
        sequence: 6,
        type: "TEST_FAILED",
        testId: "test-a",
        testDisplayName: "a()",
        detail: "boom",
      }),
    );

    expect(state.status).toEqual({ kind: "active" });
    const test = state.testsById.get("test-a");
    expect(test?.status).toBe("FAILED");
    expect(test?.steps.size).toBe(2);
    expect(test?.steps.get("step-1")).toMatchObject({
      stepName: "open homepage",
      status: "PASSED",
      firstSequence: 2,
    });
    expect(test?.steps.get("step-2")).toMatchObject({
      stepName: "assert confirmation banner",
      status: "FAILED",
      detail: "boom",
      firstSequence: 4,
    });
  });

  it("flags a protocol error when a STEP_* event references an unknown testId", () => {
    let state = createInitialRunEventStreamState();
    state = apply(
      state,
      event({
        sequence: 1,
        type: "STEP_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
        stepId: "step-1",
        stepName: "open homepage",
      }),
    );

    expect(state.status).toEqual({
      kind: "protocol-error",
      reason: 'STEP_STARTED at sequence 1 references unknown testId "test-a"',
    });
  });

  it("flags a protocol error when STEP_PASSED arrives with no prior STEP_STARTED", () => {
    let state = createInitialRunEventStreamState();
    state = apply(
      state,
      event({
        sequence: 1,
        type: "TEST_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );

    state = apply(
      state,
      event({
        sequence: 2,
        type: "STEP_PASSED",
        testId: "test-a",
        testDisplayName: "a()",
        stepId: "step-1",
        stepName: "open homepage",
      }),
    );

    expect(state.status).toEqual({
      kind: "protocol-error",
      reason:
        'STEP_PASSED at sequence 2 references step "step-1" that never received STEP_STARTED',
    });
  });

  it("flags a protocol error when STEP_FAILED arrives with no prior STEP_STARTED", () => {
    let state = createInitialRunEventStreamState();
    state = apply(
      state,
      event({
        sequence: 1,
        type: "TEST_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );

    state = apply(
      state,
      event({
        sequence: 2,
        type: "STEP_FAILED",
        testId: "test-a",
        testDisplayName: "a()",
        stepId: "step-1",
        stepName: "open homepage",
        detail: "boom",
      }),
    );

    expect(state.status.kind).toBe("protocol-error");
  });

  it("flags a protocol error when STEP_STARTED repeats an already-known step", () => {
    let state = createInitialRunEventStreamState();
    state = apply(
      state,
      event({
        sequence: 1,
        type: "TEST_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );
    state = apply(
      state,
      event({
        sequence: 2,
        type: "STEP_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
        stepId: "step-1",
        stepName: "open homepage",
      }),
    );

    state = apply(
      state,
      event({
        sequence: 3,
        type: "STEP_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
        stepId: "step-1",
        stepName: "open homepage",
      }),
    );

    expect(state.status).toEqual({
      kind: "protocol-error",
      reason: 'STEP_STARTED at sequence 3 repeats already-known step "step-1"',
    });
  });

  it("flags a protocol error when a step's stepName changes between events", () => {
    let state = createInitialRunEventStreamState();
    state = apply(
      state,
      event({
        sequence: 1,
        type: "TEST_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );
    state = apply(
      state,
      event({
        sequence: 2,
        type: "STEP_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
        stepId: "step-1",
        stepName: "open homepage",
      }),
    );

    state = apply(
      state,
      event({
        sequence: 3,
        type: "STEP_PASSED",
        testId: "test-a",
        testDisplayName: "a()",
        stepId: "step-1",
        stepName: "a different name",
      }),
    );

    expect(state.status).toEqual({
      kind: "protocol-error",
      reason:
        'STEP_PASSED at sequence 3 changed stepName for step "step-1" from "open homepage" to "a different name"',
    });
  });

  it("flags a protocol error when a test's testDisplayName changes between events", () => {
    let state = createInitialRunEventStreamState();
    state = apply(
      state,
      event({
        sequence: 1,
        type: "TEST_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );

    state = apply(
      state,
      event({
        sequence: 2,
        type: "TEST_PASSED",
        testId: "test-a",
        testDisplayName: "renamed()",
      }),
    );

    expect(state.status).toEqual({
      kind: "protocol-error",
      reason:
        'TEST_PASSED at sequence 2 changed testDisplayName for test "test-a" from "a()" to "renamed()"',
    });
  });

  it("flags a protocol error when a STEP_* event arrives after its test is already terminal", () => {
    let state = createInitialRunEventStreamState();
    state = apply(
      state,
      event({
        sequence: 1,
        type: "TEST_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );
    state = apply(
      state,
      event({
        sequence: 2,
        type: "TEST_PASSED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );

    state = apply(
      state,
      event({
        sequence: 3,
        type: "STEP_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
        stepId: "step-1",
        stepName: "too late",
      }),
    );

    expect(state.status).toEqual({
      kind: "protocol-error",
      reason:
        'STEP_STARTED at sequence 3 arrived for test "test-a" after it was already terminal (PASSED)',
    });
  });

  it("flags a protocol error when a test finishes while one of its steps is still RUNNING", () => {
    let state = createInitialRunEventStreamState();
    state = apply(
      state,
      event({
        sequence: 1,
        type: "TEST_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );
    state = apply(
      state,
      event({
        sequence: 2,
        type: "STEP_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
        stepId: "step-1",
        stepName: "never finishes",
      }),
    );

    state = apply(
      state,
      event({
        sequence: 3,
        type: "TEST_FAILED",
        testId: "test-a",
        testDisplayName: "a()",
        detail: "boom",
      }),
    );

    expect(state.status).toEqual({
      kind: "protocol-error",
      reason:
        'TEST_FAILED at sequence 3 finished test "test-a" while step "step-1" ("never finishes") was still RUNNING',
    });
  });

  it("flags a protocol error when a terminal step receives another terminal event of the same kind", () => {
    let state = createInitialRunEventStreamState();
    state = apply(
      state,
      event({
        sequence: 1,
        type: "TEST_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );
    state = apply(
      state,
      event({
        sequence: 2,
        type: "STEP_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
        stepId: "step-1",
        stepName: "open homepage",
      }),
    );
    state = apply(
      state,
      event({
        sequence: 3,
        type: "STEP_PASSED",
        testId: "test-a",
        testDisplayName: "a()",
        stepId: "step-1",
        stepName: "open homepage",
      }),
    );

    state = apply(
      state,
      event({
        sequence: 4,
        type: "STEP_PASSED",
        testId: "test-a",
        testDisplayName: "a()",
        stepId: "step-1",
        stepName: "open homepage",
      }),
    );

    expect(state.status).toEqual({
      kind: "protocol-error",
      reason:
        'STEP_PASSED at sequence 4 arrived for step "step-1" after it was already terminal (PASSED)',
    });
  });

  it("flags a protocol error when a PASSED step is later reported as FAILED", () => {
    let state = createInitialRunEventStreamState();
    state = apply(
      state,
      event({
        sequence: 1,
        type: "TEST_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );
    state = apply(
      state,
      event({
        sequence: 2,
        type: "STEP_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
        stepId: "step-1",
        stepName: "open homepage",
      }),
    );
    state = apply(
      state,
      event({
        sequence: 3,
        type: "STEP_PASSED",
        testId: "test-a",
        testDisplayName: "a()",
        stepId: "step-1",
        stepName: "open homepage",
      }),
    );

    state = apply(
      state,
      event({
        sequence: 4,
        type: "STEP_FAILED",
        testId: "test-a",
        testDisplayName: "a()",
        stepId: "step-1",
        stepName: "open homepage",
        detail: "boom",
      }),
    );

    expect(state.status).toEqual({
      kind: "protocol-error",
      reason:
        'STEP_FAILED at sequence 4 arrived for step "step-1" after it was already terminal (PASSED)',
    });
  });

  it("flags a protocol error when TEST_STARTED repeats an already-known, still-running test", () => {
    let state = createInitialRunEventStreamState();
    state = apply(
      state,
      event({
        sequence: 1,
        type: "TEST_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );

    state = apply(
      state,
      event({
        sequence: 2,
        type: "TEST_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );

    expect(state.status).toEqual({
      kind: "protocol-error",
      reason: 'TEST_STARTED at sequence 2 repeats already-known test "test-a"',
    });
  });

  it("flags a protocol error when TEST_PASSED arrives with no prior TEST_STARTED", () => {
    let state = createInitialRunEventStreamState();

    state = apply(
      state,
      event({
        sequence: 1,
        type: "TEST_PASSED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );

    expect(state.status).toEqual({
      kind: "protocol-error",
      reason:
        'TEST_PASSED at sequence 1 references test "test-a" that never received TEST_STARTED',
    });
  });

  it("flags a protocol error when TEST_FAILED arrives with no prior TEST_STARTED", () => {
    let state = createInitialRunEventStreamState();

    state = apply(
      state,
      event({
        sequence: 1,
        type: "TEST_FAILED",
        testId: "test-a",
        testDisplayName: "a()",
        detail: "boom",
      }),
    );

    expect(state.status.kind).toBe("protocol-error");
  });

  it("flags a protocol error when TEST_ABORTED arrives with no prior TEST_STARTED", () => {
    let state = createInitialRunEventStreamState();

    state = apply(
      state,
      event({
        sequence: 1,
        type: "TEST_ABORTED",
        testId: "test-a",
        testDisplayName: "a()",
        detail: "aborted",
      }),
    );

    expect(state.status.kind).toBe("protocol-error");
  });

  it("flags a protocol error when TEST_SKIPPED repeats an already-known test", () => {
    let state = createInitialRunEventStreamState();
    state = apply(
      state,
      event({
        sequence: 1,
        type: "TEST_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );

    state = apply(
      state,
      event({
        sequence: 2,
        type: "TEST_SKIPPED",
        testId: "test-a",
        testDisplayName: "a()",
        detail: "not ready yet",
      }),
    );

    expect(state.status).toEqual({
      kind: "protocol-error",
      reason: 'TEST_SKIPPED at sequence 2 repeats already-known test "test-a"',
    });
  });

  it("flags a protocol error when a second TEST_* event arrives after the test is already terminal", () => {
    let state = createInitialRunEventStreamState();
    state = apply(
      state,
      event({
        sequence: 1,
        type: "TEST_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );
    state = apply(
      state,
      event({
        sequence: 2,
        type: "TEST_PASSED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );

    state = apply(
      state,
      event({
        sequence: 3,
        type: "TEST_FAILED",
        testId: "test-a",
        testDisplayName: "a()",
        detail: "boom",
      }),
    );

    expect(state.status).toEqual({
      kind: "protocol-error",
      reason:
        'TEST_FAILED at sequence 3 arrived for test "test-a" after it was already terminal (PASSED)',
    });
  });

  it("a test that never uses Steps keeps an empty step map", () => {
    let state = createInitialRunEventStreamState();
    state = apply(
      state,
      event({
        sequence: 1,
        type: "TEST_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );
    state = apply(
      state,
      event({
        sequence: 2,
        type: "TEST_PASSED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );

    expect(state.testsById.get("test-a")?.steps.size).toBe(0);
  });

  it("treats replay and live delivery through the identical code path", () => {
    // A client reconnecting mid-run replays 1..2, then continues live from 3 - same function,
    // same rules, no special-casing needed by the caller.
    let replayed = createInitialRunEventStreamState();
    replayed = apply(replayed, event({ sequence: 1, type: "RUN_QUEUED" }));
    replayed = apply(replayed, event({ sequence: 2, type: "RUN_STARTED" }));

    let live = createInitialRunEventStreamState();
    live = apply(live, event({ sequence: 1, type: "RUN_QUEUED" }));
    live = apply(live, event({ sequence: 2, type: "RUN_STARTED" }));

    expect(replayed).toEqual(live);
  });

  it("ignores an already-seen sequence as a benign replay duplicate when the content is identical", () => {
    let state = createInitialRunEventStreamState();
    state = apply(state, event({ sequence: 1, type: "RUN_QUEUED" }));
    const afterFirst = state;

    state = apply(state, event({ sequence: 1, type: "RUN_QUEUED" }));

    expect(state).toBe(afterFirst);
    expect(state.status).toEqual({ kind: "active" });
  });

  it("flags a protocol error (not a benign duplicate) when the same sequence carries different content", () => {
    let state = createInitialRunEventStreamState();
    state = apply(state, event({ sequence: 1, type: "RUN_QUEUED" }));

    // Same runId, same sequence, but a different event type - the canonical journal's own
    // gapless-sequence guarantee means this can never legitimately happen; it must be surfaced as
    // corruption, not smoothed over as a duplicate.
    state = apply(state, event({ sequence: 1, type: "RUN_STARTED" }));

    expect(state.status).toEqual({
      kind: "protocol-error",
      reason: "event at sequence 1 conflicts with a previously seen event",
    });

    // Frozen from here too, same as any other protocol-error.
    const afterConflict = state;
    state = apply(state, event({ sequence: 2, type: "RUN_STARTED" }));
    expect(state).toBe(afterConflict);
  });

  it("flags a sequence gap without applying the out-of-order event", () => {
    let state = createInitialRunEventStreamState();
    state = apply(state, event({ sequence: 1, type: "RUN_QUEUED" }));

    state = apply(state, event({ sequence: 3, type: "RUN_STARTED" }));

    expect(state.status).toEqual({
      kind: "gap",
      expectedSequence: 2,
      receivedSequence: 3,
    });
    expect(state.lastSequence).toBe(1);
    expect(state.eventsBySequence.has(3)).toBe(false);
  });

  it("freezes after a gap - further messages are ignored", () => {
    let state = createInitialRunEventStreamState();
    state = apply(state, event({ sequence: 1, type: "RUN_QUEUED" }));
    state = apply(state, event({ sequence: 3, type: "RUN_STARTED" }));
    const afterGap = state;

    state = apply(state, event({ sequence: 2, type: "RUN_STARTED" }));

    expect(state).toBe(afterGap);
  });

  it("flags a protocol error for an event carrying a different runId", () => {
    let state = createInitialRunEventStreamState();
    state = apply(
      state,
      event({ sequence: 1, type: "RUN_QUEUED", runId: "some-other-run" }),
    );

    expect(state.status.kind).toBe("protocol-error");
  });

  it("flags a protocol error for malformed JSON", () => {
    let state = createInitialRunEventStreamState();
    state = apply(state, "{not json");

    expect(state.status).toEqual({
      kind: "protocol-error",
      reason: "malformed JSON",
    });
  });

  it("flags a protocol error for JSON with no schemaVersion/runId envelope at all", () => {
    let state = createInitialRunEventStreamState();
    state = apply(state, JSON.stringify({ hello: "world" }));

    expect(state.status.kind).toBe("protocol-error");
  });

  it("flags a protocol error for a supported-version event that still doesn't match the V1 shape", () => {
    let state = createInitialRunEventStreamState();
    // Valid envelope and version, but TEST_STARTED requires testId/testDisplayName.
    state = apply(state, event({ sequence: 1, type: "TEST_STARTED" }));

    expect(state.status.kind).toBe("protocol-error");
  });

  it("flags a compatibility error for an unsupported schema version", () => {
    let state = createInitialRunEventStreamState();
    state = apply(
      state,
      event({ sequence: 1, type: "RUN_QUEUED", schemaVersion: "2.0" }),
    );

    expect(state.status).toEqual({
      kind: "compatibility-error",
      receivedSchemaVersion: "2.0",
    });
  });

  it("flags a protocol error - not a compatibility error - for a blank schemaVersion", () => {
    // A blank schemaVersion is malformed, not a legitimate (if unsupported) future version - it
    // must fail at the envelope stage, before ever reaching the version comparison.
    let state = createInitialRunEventStreamState();
    state = apply(
      state,
      event({ sequence: 1, type: "RUN_QUEUED", schemaVersion: "   " }),
    );

    expect(state.status.kind).toBe("protocol-error");
  });

  it("flags a compatibility error - not a protocol error - for an unsupported version carrying an unrecognized V2 event type", () => {
    // The realistic future case this ordering exists for: a V2 event with a brand-new `type` this
    // build has never heard of must still be recognized as a version problem, not misclassified as
    // "malformed" just because the strict V1 union has no member for that type.
    let state = createInitialRunEventStreamState();
    state = apply(
      state,
      event({
        sequence: 1,
        type: "STEP_STARTED",
        schemaVersion: "2.0",
        stepId: "step-1",
      }),
    );

    expect(state.status).toEqual({
      kind: "compatibility-error",
      receivedSchemaVersion: "2.0",
    });
  });

  it("records runStartedAt once, from RUN_STARTED's own timestamp", () => {
    let state = createInitialRunEventStreamState();
    expect(state.runStartedAt).toBeUndefined();

    state = apply(state, event({ sequence: 1, type: "RUN_QUEUED" }));
    expect(state.runStartedAt).toBeUndefined();

    state = apply(
      state,
      event({
        sequence: 2,
        type: "RUN_STARTED",
        timestamp: "2026-08-31T20:29:00Z",
      }),
    );
    expect(state.runStartedAt).toBe("2026-08-31T20:29:00Z");

    state = apply(
      state,
      event({
        sequence: 3,
        type: "TEST_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    );
    expect(state.runStartedAt).toBe("2026-08-31T20:29:00Z");
  });

  it("records runFinishedAt from RUN_FINISHED's own timestamp - the primary terminal-time signal", () => {
    let state = createInitialRunEventStreamState();
    expect(state.runFinishedAt).toBeUndefined();

    state = apply(
      state,
      event({
        sequence: 1,
        type: "RUN_FINISHED",
        runOutcome: "CANCELLED",
        timestamp: "2026-08-31T20:30:00Z",
      }),
    );

    expect(state.runFinishedAt).toBe("2026-08-31T20:30:00Z");
  });

  it("reaches a terminal state on RUN_FINISHED and freezes afterwards", () => {
    let state = createInitialRunEventStreamState();
    state = apply(state, event({ sequence: 1, type: "RUN_QUEUED" }));
    state = apply(
      state,
      event({ sequence: 2, type: "RUN_FINISHED", runOutcome: "SUCCEEDED" }),
    );

    expect(state.status).toEqual({ kind: "terminal", runOutcome: "SUCCEEDED" });
    expect(state.runOutcome).toBe("SUCCEEDED");

    const afterTerminal = state;
    state = apply(state, event({ sequence: 3, type: "RUN_STARTED" }));

    expect(state).toBe(afterTerminal);
  });
});
