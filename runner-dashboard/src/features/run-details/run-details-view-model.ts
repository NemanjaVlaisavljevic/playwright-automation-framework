import type { ArtifactSummaryResponse } from "../../api/runner-api";
import { isTerminalRunStatus, type RunStatus } from "../../domain/run";
import type {
  StepExecution,
  StepExecutionStatus,
  TestExecution,
  TestExecutionStatus,
} from "../event-stream/run-event-reducer";

/**
 * Display-only status: relabels a test/step still `RUNNING` once the run itself is terminal (it
 * can never receive a real terminal event afterward). Never appears on the wire or in the reducer.
 */
export type DisplayTestStatus = TestExecutionStatus | "INTERRUPTED";
export type DisplayStepStatus = StepExecutionStatus | "INTERRUPTED";

const INTERRUPTED_TEST_DETAIL =
  "Run ended before this test reported a terminal result.";
const INTERRUPTED_STEP_DETAIL =
  "Run ended before this step reported a terminal result.";

export interface DisplayStep {
  readonly stepId: string;
  readonly stepName: string;
  readonly status: DisplayStepStatus;
  readonly firstSequence: number;
  readonly startedAt?: string;
  readonly finishedAt?: string;
  readonly detail?: string;
  /** `true` only for a step this view model itself relabeled - never true for a real `FAILED`. */
  readonly interrupted: boolean;
  readonly artifacts: readonly ArtifactSummaryResponse[];
}

/**
 * The one failure to show without hunting the page: the failed step's own detail (`scope: "step"`),
 * or the test's own when no step explains it (`scope: "test"`). A "step" failure is also rendered
 * by that step's row once expanded, so a collapsed-row preview should hide on expand; a "test"
 * failure has nowhere else to appear and must stay visible regardless.
 */
export type DisplayTestFailure =
  | {
      readonly scope: "step";
      readonly stepId: string;
      readonly stepName: string;
      readonly detail?: string;
      readonly artifacts: readonly ArtifactSummaryResponse[];
    }
  | {
      readonly scope: "test";
      readonly detail?: string;
      readonly artifacts: readonly ArtifactSummaryResponse[];
    };

export interface DisplayTest {
  readonly testId: string;
  readonly testDisplayName: string;
  readonly status: DisplayTestStatus;
  readonly firstSequence: number;
  readonly startedAt?: string;
  readonly finishedAt?: string;
  readonly detail?: string;
  readonly interrupted: boolean;
  readonly steps: readonly DisplayStep[];
  /** Present only when `status` is `FAILED` or `ABORTED` - see {@link DisplayTestFailure}. */
  readonly primaryFailure?: DisplayTestFailure;
  /** True if this test's own artifacts or any of its steps' artifacts are non-empty. */
  readonly hasArtifacts: boolean;
}

/** A stable, valid DOM id for "Jump to first failure" - also reused as-is by C4.5's deep links. */
export function testRowElementId(testId: string): string {
  return `test-${encodeURIComponent(testId)}`;
}

/**
 * A stable DOM id for one step's row. `stepId` alone isn't unique run-wide, so both ids are
 * encoded together; the testId segment is length-prefixed to avoid collisions since `-` survives
 * `encodeURIComponent` unescaped (a naive join could let two different pairs collide).
 */
export function stepRowElementId(testId: string, stepId: string): string {
  const encodedTestId = encodeURIComponent(testId);
  return `step-${encodedTestId.length}-${encodedTestId}-${encodeURIComponent(stepId)}`;
}

export interface RunDetailsViewModel {
  readonly tests: readonly DisplayTest[];
  readonly counts: Record<DisplayTestStatus, number>;
  /** `tests.length - counts.RUNNING` - an `INTERRUPTED` test counts as completed, not running. */
  readonly completedCount: number;
  /** True means the event stream was inconsistent - a SUCCEEDED run can't legitimately have an incomplete test. */
  readonly hasIncompleteTestsDespiteSucceededRun: boolean;
}

export function buildRunDetailsViewModel(params: {
  testsById: ReadonlyMap<string, TestExecution>;
  artifacts: readonly ArtifactSummaryResponse[];
  runStatus: RunStatus | undefined;
  runFinishedAt: string | undefined;
}): RunDetailsViewModel {
  const { testsById, artifacts, runStatus, runFinishedAt } = params;
  const runIsTerminal =
    runStatus !== undefined && isTerminalRunStatus(runStatus);
  const artifactsByStepKey = groupArtifactsByStepKey(artifacts);
  const testLevelArtifactsByTestId = groupTestLevelArtifactsByTestId(artifacts);

  const tests = Array.from(testsById.values())
    .sort((a, b) => a.firstSequence - b.firstSequence)
    .map((test) =>
      toDisplayTest(
        test,
        runIsTerminal,
        runFinishedAt,
        artifactsByStepKey,
        testLevelArtifactsByTestId.get(test.testId) ?? [],
      ),
    );

  const counts = countByDisplayStatus(tests.map((test) => test.status));
  const completedCount = tests.length - counts.RUNNING;
  const hasIncompleteTestsDespiteSucceededRun =
    runStatus === "SUCCEEDED" && tests.some((test) => test.interrupted);

  return {
    tests,
    counts,
    completedCount,
    hasIncompleteTestsDespiteSucceededRun,
  };
}

function toDisplayTest(
  test: TestExecution,
  runIsTerminal: boolean,
  runFinishedAt: string | undefined,
  artifactsByStepKey: ReadonlyMap<string, readonly ArtifactSummaryResponse[]>,
  testLevelArtifacts: readonly ArtifactSummaryResponse[],
): DisplayTest {
  const interrupted = runIsTerminal && test.status === "RUNNING";
  const steps = Array.from(test.steps.values())
    .sort((a, b) => a.firstSequence - b.firstSequence)
    .map((step) =>
      toDisplayStep(
        test.testId,
        step,
        runIsTerminal,
        runFinishedAt,
        artifactsByStepKey,
      ),
    );

  const finishedAt = interrupted ? runFinishedAt : test.finishedAt;
  const detail = interrupted
    ? (test.detail ?? INTERRUPTED_TEST_DETAIL)
    : test.detail;
  const status = interrupted ? "INTERRUPTED" : test.status;
  const primaryFailure = computePrimaryFailure(
    status,
    steps,
    detail,
    testLevelArtifacts,
  );
  const hasArtifacts =
    testLevelArtifacts.length > 0 ||
    steps.some((step) => step.artifacts.length > 0);
  return {
    testId: test.testId,
    testDisplayName: test.testDisplayName,
    firstSequence: test.firstSequence,
    status,
    interrupted,
    steps,
    hasArtifacts,
    ...(test.startedAt !== undefined ? { startedAt: test.startedAt } : {}),
    // Falls back to the run's own finishedAt so duration stops advancing instead of ticking forever.
    ...(finishedAt !== undefined ? { finishedAt } : {}),
    ...(detail !== undefined ? { detail } : {}),
    ...(primaryFailure !== undefined ? { primaryFailure } : {}),
  };
}

/**
 * Prefers the step whose FAILED outcome explains a FAILED/ABORTED test, falling back to the
 * test's own detail/artifacts. `undefined` for any other status.
 */
function computePrimaryFailure(
  status: DisplayTestStatus,
  steps: readonly DisplayStep[],
  testDetail: string | undefined,
  testLevelArtifacts: readonly ArtifactSummaryResponse[],
): DisplayTestFailure | undefined {
  if (status !== "FAILED" && status !== "ABORTED") {
    return undefined;
  }
  const failedStep = steps.find((step) => step.status === "FAILED");
  if (failedStep !== undefined) {
    return {
      scope: "step",
      stepId: failedStep.stepId,
      stepName: failedStep.stepName,
      artifacts: failedStep.artifacts,
      ...(failedStep.detail !== undefined ? { detail: failedStep.detail } : {}),
    };
  }
  return {
    scope: "test",
    artifacts: testLevelArtifacts,
    ...(testDetail !== undefined ? { detail: testDetail } : {}),
  };
}

function toDisplayStep(
  testId: string,
  step: StepExecution,
  runIsTerminal: boolean,
  runFinishedAt: string | undefined,
  artifactsByStepKey: ReadonlyMap<string, readonly ArtifactSummaryResponse[]>,
): DisplayStep {
  const interrupted = runIsTerminal && step.status === "RUNNING";
  const finishedAt = interrupted ? runFinishedAt : step.finishedAt;
  const detail = interrupted
    ? (step.detail ?? INTERRUPTED_STEP_DETAIL)
    : step.detail;
  return {
    stepId: step.stepId,
    stepName: step.stepName,
    firstSequence: step.firstSequence,
    status: interrupted ? "INTERRUPTED" : step.status,
    interrupted,
    artifacts:
      artifactsByStepKey.get(artifactStepKey(testId, step.stepId)) ?? [],
    ...(step.startedAt !== undefined ? { startedAt: step.startedAt } : {}),
    ...(finishedAt !== undefined ? { finishedAt } : {}),
    ...(detail !== undefined ? { detail } : {}),
  };
}

function countByDisplayStatus(
  statuses: readonly DisplayTestStatus[],
): Record<DisplayTestStatus, number> {
  const counts: Record<DisplayTestStatus, number> = {
    RUNNING: 0,
    PASSED: 0,
    FAILED: 0,
    ABORTED: 0,
    SKIPPED: 0,
    INTERRUPTED: 0,
  };
  for (const status of statuses) {
    counts[status] += 1;
  }
  return counts;
}

/**
 * `stepId` is scoped to one test, not globally unique, so grouping keys on the pair. Joined with a
 * NUL separator since `testId` (a JUnit unique-id) can contain spaces or other printable characters.
 */
function artifactStepKey(testId: string, stepId: string): string {
  return testId + "\u0000" + stepId;
}

function groupArtifactsByStepKey(
  artifacts: readonly ArtifactSummaryResponse[],
): ReadonlyMap<string, ArtifactSummaryResponse[]> {
  const map = new Map<string, ArtifactSummaryResponse[]>();
  for (const artifact of artifacts) {
    if (artifact.stepId === undefined) {
      continue;
    }
    const key = artifactStepKey(artifact.testId, artifact.stepId);
    const existing = map.get(key);
    if (existing !== undefined) {
      existing.push(artifact);
    } else {
      map.set(key, [artifact]);
    }
  }
  return map;
}

/** Artifacts with no `stepId` (a test that never used the `Steps` API), grouped by `testId` alone. */
function groupTestLevelArtifactsByTestId(
  artifacts: readonly ArtifactSummaryResponse[],
): ReadonlyMap<string, ArtifactSummaryResponse[]> {
  const map = new Map<string, ArtifactSummaryResponse[]>();
  for (const artifact of artifacts) {
    if (artifact.stepId !== undefined) {
      continue;
    }
    const existing = map.get(artifact.testId);
    if (existing !== undefined) {
      existing.push(artifact);
    } else {
      map.set(artifact.testId, [artifact]);
    }
  }
  return map;
}
