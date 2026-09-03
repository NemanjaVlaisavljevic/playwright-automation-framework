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

  const tests = Array.from(testsById.values())
    .sort((a, b) => a.firstSequence - b.firstSequence)
    .map((test) =>
      toDisplayTest(test, runIsTerminal, runFinishedAt, artifactsByStepKey),
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
  return {
    testId: test.testId,
    testDisplayName: test.testDisplayName,
    firstSequence: test.firstSequence,
    status: interrupted ? "INTERRUPTED" : test.status,
    interrupted,
    steps,
    ...(test.startedAt !== undefined ? { startedAt: test.startedAt } : {}),
    // A still-RUNNING test/step never has its own finishedAt - using the run's own finishedAt as
    // the display end point means its duration stops advancing once shown, rather than continuing
    // to tick against `Date.now()` (`runDurationMs`'s default) forever after the run is long over.
    ...(finishedAt !== undefined ? { finishedAt } : {}),
    ...(detail !== undefined ? { detail } : {}),
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
