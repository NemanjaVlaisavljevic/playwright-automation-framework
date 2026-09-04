import { describe, expect, it } from "vitest";
import type { DisplayStep, DisplayTest } from "./run-details-view-model";
import {
  buildRunResultUrl,
  computeDeepLinkStatus,
  describeDeepLinkStatus,
  parseRunResultTarget,
  runResultTargetKey,
  type RunResultTarget,
} from "./run-result-target";

function displayStep(overrides: Partial<DisplayStep> = {}): DisplayStep {
  return {
    stepId: "step-1",
    stepName: "do something",
    status: "PASSED",
    firstSequence: 1,
    interrupted: false,
    artifacts: [],
    ...overrides,
  };
}

function displayTest(overrides: Partial<DisplayTest> = {}): DisplayTest {
  return {
    testId: "test-1",
    testDisplayName: "aTest()",
    status: "PASSED",
    firstSequence: 1,
    interrupted: false,
    steps: [],
    hasArtifacts: false,
    ...overrides,
  };
}

describe("parseRunResultTarget", () => {
  it("is 'none' when neither testId nor stepId is present at all", () => {
    expect(parseRunResultTarget(new URLSearchParams(""))).toEqual({
      kind: "none",
    });
  });

  it("parses a valid test-only target", () => {
    const parsed = parseRunResultTarget(new URLSearchParams("testId=t-1"));
    expect(parsed).toEqual({
      kind: "valid",
      target: { kind: "test", testId: "t-1" },
    });
  });

  it("parses a valid test+step target", () => {
    const parsed = parseRunResultTarget(
      new URLSearchParams("testId=t-1&stepId=s-1"),
    );
    expect(parsed).toEqual({
      kind: "valid",
      target: { kind: "step", testId: "t-1", stepId: "s-1" },
    });
  });

  it("round-trips a real JUnit unique id through the query string", () => {
    const junitTestId =
      "[engine:junit-jupiter]/[class:BookingJourneyTest]/[method:verifiesBooking()]";
    const url = new URL(
      buildRunResultUrl("run-1", { kind: "test", testId: junitTestId }),
    );
    const parsed = parseRunResultTarget(url.searchParams);
    expect(parsed).toEqual({
      kind: "valid",
      target: { kind: "test", testId: junitTestId },
    });
  });

  it("is 'invalid' for a blank testId - a deep link was attempted, not simply absent", () => {
    expect(parseRunResultTarget(new URLSearchParams("testId=%20%20"))).toEqual({
      kind: "invalid",
    });
  });

  it("is 'invalid' for a stepId with no testId", () => {
    expect(parseRunResultTarget(new URLSearchParams("stepId=s-1"))).toEqual({
      kind: "invalid",
    });
  });

  it("is 'invalid' for a blank stepId", () => {
    expect(
      parseRunResultTarget(new URLSearchParams("testId=t-1&stepId=%20")),
    ).toEqual({ kind: "invalid" });
  });
});

describe("buildRunResultUrl", () => {
  it("builds an absolute test-only URL", () => {
    const url = buildRunResultUrl("run-1", { kind: "test", testId: "t-1" });
    expect(url).toBe(`${window.location.origin}/runs/run-1?testId=t-1`);
  });

  it("builds an absolute test+step URL", () => {
    const url = buildRunResultUrl("run-1", {
      kind: "step",
      testId: "t-1",
      stepId: "s-1",
    });
    expect(url).toBe(
      `${window.location.origin}/runs/run-1?testId=t-1&stepId=s-1`,
    );
  });
});

describe("runResultTargetKey", () => {
  it("distinguishes a test target from a step target on the same testId", () => {
    const testTarget: RunResultTarget = { kind: "test", testId: "t-1" };
    const stepTarget: RunResultTarget = {
      kind: "step",
      testId: "t-1",
      stepId: "s-1",
    };
    expect(runResultTargetKey(testTarget)).not.toBe(
      runResultTargetKey(stepTarget),
    );
  });

  it("distinguishes two different tests sharing the same stepId", () => {
    const a: RunResultTarget = {
      kind: "step",
      testId: "t-a",
      stepId: "shared",
    };
    const b: RunResultTarget = {
      kind: "step",
      testId: "t-b",
      stepId: "shared",
    };
    expect(runResultTargetKey(a)).not.toBe(runResultTargetKey(b));
  });
});

describe("computeDeepLinkStatus", () => {
  it("is 'none' when there is no target", () => {
    expect(computeDeepLinkStatus(undefined, [], "LIVE")).toEqual({
      kind: "none",
    });
  });

  it("is 'found' once the target test exists", () => {
    const tests = [displayTest({ testId: "t-1" })];
    const status = computeDeepLinkStatus(
      { kind: "test", testId: "t-1" },
      tests,
      "LIVE",
    );
    expect(status).toEqual({ kind: "found" });
  });

  it("is 'found' once the target step exists within its test", () => {
    const tests = [
      displayTest({
        testId: "t-1",
        steps: [displayStep({ stepId: "s-1" })],
      }),
    ];
    const status = computeDeepLinkStatus(
      { kind: "step", testId: "t-1", stepId: "s-1" },
      tests,
      "LIVE",
    );
    expect(status).toEqual({ kind: "found" });
  });

  it("is 'waiting' while not yet found and the connection is still LIVE/CONNECTING/RECONNECTING", () => {
    for (const state of ["CONNECTING", "LIVE", "RECONNECTING"] as const) {
      expect(
        computeDeepLinkStatus({ kind: "test", testId: "t-1" }, [], state),
      ).toEqual({ kind: "waiting" });
    }
  });

  /**
   * Regression test (review finding, P1): on a fresh deep-link load against an already-finished
   * run, `GET /runs/:id` routinely resolves *before* the SSE replay has delivered every event. A
   * REST-derived "is the run terminal" boolean would report "not found" for a target only a few
   * replayed events away - `computeDeepLinkStatus` must instead wait for the *stream's own* `CLOSED`
   * state, which only ever happens once the reducer has actually processed `RUN_FINISHED`.
   */
  it("stays 'waiting', never a premature not-found, while the connection has not yet reached CLOSED - even against an already-terminal run", () => {
    for (const state of ["CONNECTING", "LIVE", "RECONNECTING"] as const) {
      const status = computeDeepLinkStatus(
        { kind: "test", testId: "t-1" },
        [], // the replay has not delivered the target (or anything) yet
        state,
      );
      expect(status).toEqual({ kind: "waiting" });
    }
  });

  it("is 'waiting', never 'not found', while RECOVERING", () => {
    const status = computeDeepLinkStatus(
      { kind: "test", testId: "t-1" },
      [],
      "RECOVERING",
    );
    expect(status).toEqual({ kind: "waiting" });
  });

  it("is 'unavailable' on PROTOCOL_ERROR before the target is found", () => {
    const status = computeDeepLinkStatus(
      { kind: "test", testId: "t-1" },
      [],
      "PROTOCOL_ERROR",
    );
    expect(status).toEqual({ kind: "unavailable" });
  });

  it("is 'test-not-found' once the connection is CLOSED and the test never appeared", () => {
    const status = computeDeepLinkStatus(
      { kind: "test", testId: "t-1" },
      [],
      "CLOSED",
    );
    expect(status).toEqual({ kind: "test-not-found" });
  });

  it("is 'step-not-found' once the connection is CLOSED, the test exists, but the step never appeared", () => {
    const tests = [displayTest({ testId: "t-1", steps: [] })];
    const status = computeDeepLinkStatus(
      { kind: "step", testId: "t-1", stepId: "s-1" },
      tests,
      "CLOSED",
    );
    expect(status).toEqual({ kind: "step-not-found" });
  });
});

describe("describeDeepLinkStatus", () => {
  it("describes an invalid link", () => {
    expect(describeDeepLinkStatus({ kind: "invalid" })).toBe(
      "This result link is invalid.",
    );
  });
});
