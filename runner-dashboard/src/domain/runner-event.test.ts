import { describe, expect, it } from "vitest";
import { isTestLevelEvent, RunnerEvent } from "./runner-event";

const runQueued = {
  schemaVersion: "1.0",
  runId: "run-1",
  sequence: 1,
  timestamp: "2026-08-31T20:28:52.909407300Z",
  type: "RUN_QUEUED",
};

const testStarted = {
  schemaVersion: "1.0",
  runId: "run-1",
  sequence: 3,
  timestamp: "2026-08-31T20:28:53Z",
  type: "TEST_STARTED",
  testId: "[engine:junit-jupiter]/[class:Fixture]/[method:passing()]",
  testDisplayName: "passing()",
};

describe("RunnerEvent", () => {
  it("parses a run-level event with only the base fields", () => {
    const result = RunnerEvent.safeParse(runQueued);
    expect(result.success).toBe(true);
  });

  it("parses a test-level event requiring testId and testDisplayName", () => {
    const result = RunnerEvent.safeParse(testStarted);
    expect(result.success).toBe(true);
  });

  it("rejects a test-level event missing testId", () => {
    const { testId, ...withoutTestId } = testStarted;
    void testId;
    expect(RunnerEvent.safeParse(withoutTestId).success).toBe(false);
  });

  it("requires runOutcome on RUN_FINISHED", () => {
    const withoutOutcome = { ...runQueued, type: "RUN_FINISHED" };
    expect(RunnerEvent.safeParse(withoutOutcome).success).toBe(false);

    const withOutcome = { ...withoutOutcome, runOutcome: "SUCCEEDED" };
    expect(RunnerEvent.safeParse(withOutcome).success).toBe(true);
  });

  it("rejects an unrecognized event type", () => {
    expect(
      RunnerEvent.safeParse({ ...runQueued, type: "STEP_STARTED" }).success,
    ).toBe(false);
  });

  it("rejects a non-positive sequence", () => {
    expect(RunnerEvent.safeParse({ ...runQueued, sequence: 0 }).success).toBe(
      false,
    );
  });

  it("accepts detail as optional, and rejects it if present as null (omitted, not nullable)", () => {
    expect(
      RunnerEvent.safeParse({
        ...runQueued,
        detail: "queued behind 2 other runs",
      }).success,
    ).toBe(true);
    expect(RunnerEvent.safeParse({ ...runQueued, detail: null }).success).toBe(
      false,
    );
  });

  it("rejects a run-level event carrying runOutcome (only RUN_FINISHED may)", () => {
    expect(
      RunnerEvent.safeParse({ ...runQueued, runOutcome: "SUCCEEDED" }).success,
    ).toBe(false);
  });

  it("rejects a run-level event carrying a test identifier", () => {
    expect(
      RunnerEvent.safeParse({ ...runQueued, testId: "some-test" }).success,
    ).toBe(false);
  });

  it("rejects a test-level event carrying runOutcome (only RUN_FINISHED may)", () => {
    expect(
      RunnerEvent.safeParse({ ...testStarted, runOutcome: "SUCCEEDED" })
        .success,
    ).toBe(false);
  });

  it.each(["schemaVersion", "runId", "testId", "testDisplayName"])(
    "rejects a blank (whitespace-only or empty) %s",
    (field) => {
      expect(
        RunnerEvent.safeParse({ ...testStarted, [field]: "   " }).success,
      ).toBe(false);
      expect(
        RunnerEvent.safeParse({ ...testStarted, [field]: "" }).success,
      ).toBe(false);
    },
  );
});

describe("isTestLevelEvent", () => {
  it("distinguishes test-level from run-level events", () => {
    const queued = RunnerEvent.parse(runQueued);
    const started = RunnerEvent.parse(testStarted);

    expect(isTestLevelEvent(queued)).toBe(false);
    expect(isTestLevelEvent(started)).toBe(true);
  });
});
