import type { DisplayTest, DisplayTestStatus } from "./run-details-view-model";

/** `"ALL"`/`"PROBLEMS"` are display-only groupings; every other value is a real `DisplayTestStatus`. */
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
  /** Step-name search matches within this test, by `stepId`; no highlighting rendered from this yet. */
  readonly matchedStepIds: ReadonlySet<string>;
  /** True when a step matched, so `TestResultsSection` can temporarily show the step list for context. */
  readonly forceExpandedForSearch: boolean;
}

/** Statuses that read as "something went wrong" to a viewer - grouped under "Problems". */
const PROBLEM_STATUSES: ReadonlySet<DisplayTestStatus> = new Set([
  "FAILED",
  "ABORTED",
  "INTERRUPTED",
]);

/** Pure filter over already-computed `DisplayTest`s. Preserves `tests`' order; never re-sorts. */
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

/** Never searches failure detail/stack trace text - too noisy and expensive over a large result set. */
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
