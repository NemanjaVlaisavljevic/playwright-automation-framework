import { describe, expect, it } from "vitest";
import type { ArtifactSummaryResponse } from "../../api/runner-api";
import type {
  StepExecution,
  TestExecution,
} from "../event-stream/run-event-reducer";
import { buildRunDetailsViewModel } from "./run-details-view-model";

function test(overrides: Partial<TestExecution> = {}): TestExecution {
  return {
    testId: "test-1",
    testDisplayName: "aTest()",
    status: "RUNNING",
    firstSequence: 1,
    steps: new Map(),
    ...overrides,
  };
}

function step(overrides: Partial<StepExecution> = {}): StepExecution {
  return {
    stepId: "step-1",
    stepName: "do something",
    status: "RUNNING",
    firstSequence: 1,
    ...overrides,
  };
}

const RUN_FINISHED_AT = "2026-09-03T10:05:00Z";

describe("buildRunDetailsViewModel", () => {
  it("leaves a normal SUCCEEDED/FAILED flow untouched - no test or step is relabeled", () => {
    const testsById = new Map([
      [
        "test-1",
        test({
          testId: "test-1",
          status: "PASSED",
          finishedAt: "2026-09-03T10:01:00Z",
          steps: new Map([
            [
              "step-1",
              step({ status: "PASSED", finishedAt: "2026-09-03T10:00:30Z" }),
            ],
          ]),
        }),
      ],
      [
        "test-2",
        test({
          testId: "test-2",
          status: "FAILED",
          finishedAt: "2026-09-03T10:02:00Z",
          detail: "boom",
        }),
      ],
    ]);

    const model = buildRunDetailsViewModel({
      testsById,
      artifacts: [],
      runStatus: "FAILED",
      runFinishedAt: RUN_FINISHED_AT,
    });

    expect(model.tests.map((t) => t.status)).toEqual(["PASSED", "FAILED"]);
    expect(model.tests.every((t) => !t.interrupted)).toBe(true);
    expect(model.tests[0]!.steps[0]!.status).toBe("PASSED");
    expect(model.tests[0]!.steps[0]!.interrupted).toBe(false);
    expect(model.tests[1]!.detail).toBe("boom");
    expect(model.hasIncompleteTestsDespiteSucceededRun).toBe(false);
  });

  it("marks a test still RUNNING when the run is terminal (cancel) as INTERRUPTED, using the run's own finishedAt", () => {
    const testsById = new Map([
      ["test-1", test({ testId: "test-1", status: "RUNNING" })],
    ]);

    const model = buildRunDetailsViewModel({
      testsById,
      artifacts: [],
      runStatus: "CANCELLED",
      runFinishedAt: RUN_FINISHED_AT,
    });

    const displayed = model.tests[0]!;
    expect(displayed.status).toBe("INTERRUPTED");
    expect(displayed.interrupted).toBe(true);
    expect(displayed.finishedAt).toBe(RUN_FINISHED_AT);
    expect(displayed.detail).toBe(
      "Run ended before this test reported a terminal result.",
    );
  });

  it("marks only the still-RUNNING step INTERRUPTED (timeout mid-step) - earlier PASSED/FAILED steps are untouched", () => {
    const testsById = new Map([
      [
        "test-1",
        test({
          testId: "test-1",
          status: "RUNNING",
          steps: new Map([
            [
              "step-1",
              step({
                stepId: "step-1",
                status: "PASSED",
                finishedAt: "2026-09-03T10:00:10Z",
              }),
            ],
            [
              "step-2",
              step({
                stepId: "step-2",
                stepName: "still running",
                status: "RUNNING",
              }),
            ],
          ]),
        }),
      ],
    ]);

    const model = buildRunDetailsViewModel({
      testsById,
      artifacts: [],
      runStatus: "TIMED_OUT",
      runFinishedAt: RUN_FINISHED_AT,
    });

    const [firstStep, secondStep] = model.tests[0]!.steps;
    expect(firstStep!.status).toBe("PASSED");
    expect(firstStep!.interrupted).toBe(false);
    expect(firstStep!.finishedAt).toBe("2026-09-03T10:00:10Z");
    expect(secondStep!.status).toBe("INTERRUPTED");
    expect(secondStep!.interrupted).toBe(true);
    expect(secondStep!.finishedAt).toBe(RUN_FINISHED_AT);
    // The test itself is also RUNNING with no terminal event of its own - relabeled too.
    expect(model.tests[0]!.status).toBe("INTERRUPTED");
  });

  it("marks a RUNNING test INTERRUPTED after a process crash (ERROR outcome)", () => {
    const testsById = new Map([
      ["test-1", test({ testId: "test-1", status: "RUNNING" })],
    ]);

    const model = buildRunDetailsViewModel({
      testsById,
      artifacts: [],
      runStatus: "ERROR",
      runFinishedAt: RUN_FINISHED_AT,
    });

    expect(model.tests[0]!.status).toBe("INTERRUPTED");
  });

  it("counts INTERRUPTED as completed, not running, and separately from PASSED/FAILED", () => {
    const testsById = new Map([
      ["test-1", test({ testId: "test-1", status: "RUNNING" })],
      [
        "test-2",
        test({
          testId: "test-2",
          status: "PASSED",
          finishedAt: RUN_FINISHED_AT,
        }),
      ],
    ]);

    const model = buildRunDetailsViewModel({
      testsById,
      artifacts: [],
      runStatus: "CANCELLED",
      runFinishedAt: RUN_FINISHED_AT,
    });

    expect(model.counts).toEqual({
      RUNNING: 0,
      PASSED: 1,
      FAILED: 0,
      ABORTED: 0,
      SKIPPED: 0,
      INTERRUPTED: 1,
    });
    expect(model.completedCount).toBe(2);
  });

  it("does not relabel anything while the run itself is still non-terminal", () => {
    const testsById = new Map([
      ["test-1", test({ testId: "test-1", status: "RUNNING" })],
    ]);

    const model = buildRunDetailsViewModel({
      testsById,
      artifacts: [],
      runStatus: "RUNNING",
      runFinishedAt: undefined,
    });

    expect(model.tests[0]!.status).toBe("RUNNING");
    expect(model.tests[0]!.interrupted).toBe(false);
  });

  it("flags a data-integrity warning when a SUCCEEDED run still has an interrupted test", () => {
    const testsById = new Map([
      ["test-1", test({ testId: "test-1", status: "RUNNING" })],
    ]);

    const model = buildRunDetailsViewModel({
      testsById,
      artifacts: [],
      runStatus: "SUCCEEDED",
      runFinishedAt: RUN_FINISHED_AT,
    });

    expect(model.hasIncompleteTestsDespiteSucceededRun).toBe(true);
  });

  it("does not flag the integrity warning for a normal SUCCEEDED run with only terminal tests", () => {
    const testsById = new Map([
      [
        "test-1",
        test({
          testId: "test-1",
          status: "PASSED",
          finishedAt: RUN_FINISHED_AT,
        }),
      ],
    ]);

    const model = buildRunDetailsViewModel({
      testsById,
      artifacts: [],
      runStatus: "SUCCEEDED",
      runFinishedAt: RUN_FINISHED_AT,
    });

    expect(model.hasIncompleteTestsDespiteSucceededRun).toBe(false);
  });

  it("groups artifacts onto the matching (testId, stepId) step, never a different test's same-named step", () => {
    const testsById = new Map([
      [
        "test-a",
        test({
          testId: "test-a",
          testDisplayName: "aTest()",
          steps: new Map([["shared-step", step({ stepId: "shared-step" })]]),
        }),
      ],
      [
        "test-b",
        test({
          testId: "test-b",
          testDisplayName: "bTest()",
          steps: new Map([["shared-step", step({ stepId: "shared-step" })]]),
        }),
      ],
    ]);
    const artifacts: ArtifactSummaryResponse[] = [
      {
        artifactId: "trace-1",
        testId: "test-a",
        testDisplayName: "aTest()",
        stepId: "shared-step",
        type: "TRACE",
        mediaType: "application/zip",
        sizeBytes: 1024,
        createdAt: RUN_FINISHED_AT,
        downloadUrl: "/artifacts/trace-1",
      },
    ];

    const model = buildRunDetailsViewModel({
      testsById,
      artifacts,
      runStatus: "RUNNING",
      runFinishedAt: undefined,
    });

    const [testA, testB] = model.tests;
    expect(testA!.steps[0]!.artifacts).toHaveLength(1);
    expect(testB!.steps[0]!.artifacts).toHaveLength(0);
  });
});
