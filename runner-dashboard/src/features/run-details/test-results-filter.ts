import type { DisplayTest, DisplayTestStatus } from "./run-details-view-model";

/**
 * `"ALL"`/`"PROBLEMS"` are display-only groupings with no counterpart in `DisplayTestStatus` -
 * every other value is exactly a `DisplayTestStatus`, so a future status added there flows into
 * this union automatically rather than needing its own separate literal kept in sync by hand.
 */
export type TestStatusFilter = "ALL" | "PROBLEMS" | DisplayTestStatus;

export type EvidenceFilter = "ALL" | "HAS_ARTIFACTS" | "NO_ARTIFACTS";

export interface TestResultsFilter {
  readonly search: string;
  readonly status: TestStatusFilter;
  readonly evidence: EvidenceFilter;
}

export const DEFAULT_TEST_RESULTS_FILTER: TestResultsFilter = {
  search: "",
  status: "ALL",
  evidence: "ALL",
};

export interface FilteredTestResult {
  readonly test: DisplayTest;
  /** Step-name search matches within this test, by `stepId` - informational (no highlighting is
   * rendered from this yet), kept as its own field so the matching logic stays independently
   * testable from whatever the UI eventually does with it. */
  readonly matchedStepIds: ReadonlySet<string>;
  /** `true` when a step matched the current search - the caller (`TestResultsSection`) uses this
   * to temporarily show the test's full step list for context, without touching the user's own
   * manual expand/collapse choice underneath it. */
  readonly forceExpandedForSearch: boolean;
}

/**
 * `FAILED`/`ABORTED` are real terminal outcomes; `INTERRUPTED` is the view model's own display-only
 * relabeling (see `run-details-view-model.ts`) for a test that never got to report one because the
 * run ended first - all three read as "something went wrong here" to a viewer, so "Problems" groups
 * them rather than making a viewer pick each one individually.
 */
const PROBLEM_STATUSES: ReadonlySet<DisplayTestStatus> = new Set([
  "FAILED",
  "ABORTED",
  "INTERRUPTED",
]);

/**
 * Filters (and, for a step-name match, flags for temporary expansion) `tests` for the C4.4 Tests
 * section - a pure function over already-computed `DisplayTest`s, never touching the SSE reducer or
 * event/wire state. Preserves `tests`' own order (already `firstSequence`-sorted upstream) - this
 * never re-sorts, since that order is the run's real execution timeline.
 */
export function filterTestResults(
  tests: readonly DisplayTest[],
  filter: TestResultsFilter,
): readonly FilteredTestResult[] {
  const search = filter.search.trim().toLowerCase();
  const results: FilteredTestResult[] = [];
  for (const test of tests) {
    if (!matchesStatus(test, filter.status)) {
      continue;
    }
    if (!matchesEvidence(test, filter.evidence)) {
      continue;
    }
    const matchedStepIds = matchingStepIds(test, search);
    if (
      search !== "" &&
      !testNameMatches(test, search) &&
      matchedStepIds.size === 0
    ) {
      continue;
    }
    results.push({
      test,
      matchedStepIds,
      forceExpandedForSearch: matchedStepIds.size > 0,
    });
  }
  return results;
}

function matchesStatus(test: DisplayTest, status: TestStatusFilter): boolean {
  switch (status) {
    case "ALL":
      return true;
    case "PROBLEMS":
      return PROBLEM_STATUSES.has(test.status);
    default:
      return test.status === status;
  }
}

function matchesEvidence(test: DisplayTest, evidence: EvidenceFilter): boolean {
  switch (evidence) {
    case "ALL":
      return true;
    case "HAS_ARTIFACTS":
      return test.hasArtifacts;
    case "NO_ARTIFACTS":
      return !test.hasArtifacts;
  }
}

/** Never searches failure detail/stack trace text - deliberately out of scope for this phase, see
 * the C4.4 spec's own reasoning (noise, and expensive over a large result set). */
function testNameMatches(test: DisplayTest, search: string): boolean {
  return (
    test.testDisplayName.toLowerCase().includes(search) ||
    test.testId.toLowerCase().includes(search)
  );
}

function matchingStepIds(
  test: DisplayTest,
  search: string,
): ReadonlySet<string> {
  if (search === "") {
    return new Set();
  }
  const ids = new Set<string>();
  for (const step of test.steps) {
    if (step.stepName.toLowerCase().includes(search)) {
      ids.add(step.stepId);
    }
  }
  return ids;
}
