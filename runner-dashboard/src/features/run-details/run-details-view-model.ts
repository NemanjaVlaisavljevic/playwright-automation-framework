import type { ArtifactSummaryResponse } from "../../api/runner-api";
import { isTerminalRunStatus, type RunStatus } from "../../domain/run";
import type {
  StepExecution,
  StepExecutionStatus,
  TestExecution,
  TestExecutionStatus,
} from "../event-stream/run-event-reducer";

/**
 * `INTERRUPTED` exists only here, never in the SSE wire contract or the reducer's own
 * `TestExecutionStatus`/`StepExecutionStatus` - the backend never emits it, and the reducer's
 * strict lifecycle validation (`applyTestEvent`/`applyStepEvent`) must stay ignorant of it. It is
 * purely a display-time reconciliation: a test or step that is still (wire-level) `RUNNING` once
 * the run itself has reached a terminal status can never legitimately receive its own terminal
 * event afterward (the JVM that would have emitted it is gone), so the view model relabels it here
 * rather than the UI showing something that will never actually change again.
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
 * The one failure a viewer should see without hunting the rest of the page: the failed step's own
 * detail/artifacts if one exists (`scope: "step"`), or the test's own when no step explains it
 * (`scope: "test"` - a test that never used the `Steps` API, or whose `TEST_FAILED`/`TEST_ABORTED`
 * arrived without any step reporting `STEP_FAILED` first, e.g. a failure during cleanup after every
 * step already passed). The two scopes matter beyond just which fields are present: a `"step"`
 * failure is also rendered richly by that step's own row once the test is expanded, so a caller
 * showing this as a collapsed-row preview should hide it once expanded to avoid double-rendering the
 * same content - a `"test"` failure has nowhere else to appear, expanded or not, and must stay
 * visible regardless of the row's expand state.
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
  /**
   * `true` if this test's own (no-`stepId`) artifacts or any of its steps' own artifacts are
   * non-empty - the C4.4 evidence filter's whole basis. Computed here, not re-derived per caller,
   * so "has evidence" means the exact same thing everywhere it's asked (the filter, and any future
   * caller) rather than each reimplementing "test-level or any step" themselves.
   */
  readonly hasArtifacts: boolean;
}

/** A stable, valid DOM id for "Jump to first failure" - also reused as-is by C4.5's deep links. */
export function testRowElementId(testId: string): string {
  return `test-${encodeURIComponent(testId)}`;
}

/**
 * A stable, valid DOM id for one step's own row - `stepId` alone is not unique run-wide (two
 * different tests may legitimately reuse the same one, see `RunnerEvent`'s own contract), so both
 * ids must be encoded together. Length-prefixed, not joined with a bare delimiter: `-` (like most
 * URL-safe punctuation) survives `encodeURIComponent` unescaped, so a naive `${testId}-${stepId}`
 * join could let two different (testId, stepId) pairs collide on the same string (e.g. testId
 * `"a-b"` + stepId `"c"` vs. testId `"a"` + stepId `"b-c"`). Prefixing the encoded testId with its
 * own length removes that ambiguity regardless of what characters end up inside either segment -
 * this id is only ever used opaquely via `document.getElementById`, never parsed back apart, so
 * only collision-freedom matters, not reversibility.
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
  /**
   * A `SUCCEEDED` run can never legitimately have a test/step that never reported a terminal
   * result - unlike `CANCELLED`/`TIMED_OUT`/`ERROR`, nothing about a successful run explains an
   * event going missing. `true` here means the raw event stream was inconsistent, not that this is
   * an ordinary interruption a viewer should read as "the run was cancelled".
   */
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
    // A still-RUNNING test/step never has its own finishedAt - using the run's own finishedAt as
    // the display end point means its duration stops advancing once shown, rather than continuing
    // to tick against `Date.now()` (`runDurationMs`'s default) forever after the run is long over.
    ...(finishedAt !== undefined ? { finishedAt } : {}),
    ...(detail !== undefined ? { detail } : {}),
    ...(primaryFailure !== undefined ? { primaryFailure } : {}),
  };
}

/**
 * Prefers the one step whose own `FAILED` outcome most plausibly explains a `FAILED`/`ABORTED`
 * test (the reducer's own lifecycle guarantees a test cannot finish while a step is still
 * `RUNNING`, so any failed step is already terminal by the time this runs) - falls back to the
 * test's own detail/artifacts for a test that never used the `Steps` API, or whose failure wasn't
 * attributed to any single step. `undefined` for anything else (`PASSED`/`SKIPPED`/`RUNNING`/
 * `INTERRUPTED`) - there is nothing to show a dedicated failure panel for.
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
 * `stepId` is scoped to one test, not globally unique (see `RunnerEvent`'s own contract) - two
 * different tests may legitimately reuse the same `stepId`, so grouping artifacts must key on the
 * pair, never `stepId` alone. Joined with an escaped NUL separator, not a printable one - a JUnit
 * unique-id (`testId`) routinely contains spaces itself (e.g. a multi-parameter test method's own
 * `[method:...(TypeA, TypeB)]` segment), so a printable separator could let two distinct (testId,
 * stepId) pairs collide on the same joined string.
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

/**
 * An artifact with no `stepId` - a test that never used the `Steps` API, so its own top-level
 * failure is the only thing to attribute a captured screenshot/trace to. Grouped by `testId` alone;
 * unlike a step's `stepId`, `testId` (a JUnit unique ID) already is unique run-wide.
 */
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
