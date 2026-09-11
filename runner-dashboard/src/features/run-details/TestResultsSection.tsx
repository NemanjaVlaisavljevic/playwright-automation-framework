import {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from "react";
import { EmptyState } from "../../components/ui/EmptyState";
import {
  stepRowElementId,
  testRowElementId,
  type DisplayTest,
} from "./run-details-view-model";
import type { RunResultTarget } from "./run-result-target";
import {
  DEFAULT_TEST_RESULTS_FILTER,
  filterTestResults,
  type TestResultsFilter,
} from "./test-results-filter";
import { TestResultsFilters } from "./TestResultsFilters";
import { TestResultsTable } from "./TestResultsTable";
import styles from "./RunDetailsPage.module.css";

const PROBLEM_STATUSES = new Set(["FAILED", "ABORTED", "INTERRUPTED"]);

export interface TestResultsSectionHandle {
  /**
   * Reveals, scrolls to, and focuses a test or step's row - resets active filters if they hide the
   * target, and (for a step) force-expands its parent test as a real click would.
   */
  reveal(target: RunResultTarget): void;
}

export interface TestResultsSectionProps {
  runId: string;
  tests: readonly DisplayTest[];
  artifactsErrorMessage?: string;
}

/**
 * Owns how the Tests table is shown: the search/status/evidence filter, each row's manual
 * expand/collapse choice, and reveal-on-demand navigation. Filtering is display-only, computed
 * fresh via `filterTestResults` - Progress and LiveFocusPanel always see the full, unfiltered list.
 */
export const TestResultsSection = forwardRef<
  TestResultsSectionHandle,
  TestResultsSectionProps
>(function TestResultsSection({ runId, tests, artifactsErrorMessage }, ref) {
  const [filter, setFilter] = useState<TestResultsFilter>(
    DEFAULT_TEST_RESULTS_FILTER,
  );
  // Keyed by testId, not owned per-row, so a user's choice survives a filtered-out row unmounting.
  const [manualExpanded, setManualExpanded] = useState<
    ReadonlyMap<string, boolean>
  >(new Map());
  // Each reveal gets its own monotonic requestId rather than being deduplicated by target, so
  // clicking the same item twice (after scrolling away) always re-triggers scroll/focus.
  // `handledRequestIdRef` (a ref) stops the effect below from re-running for an already-handled request.
  const [pendingReveal, setPendingReveal] = useState<
    { readonly target: RunResultTarget; readonly requestId: number } | undefined
  >(undefined);
  const handledRequestIdRef = useRef<number | undefined>(undefined);
  const nextRequestIdRef = useRef(0);

  const filtered = useMemo(
    () => filterTestResults(tests, filter),
    [tests, filter],
  );
  const forceExpandedIds = useMemo(
    () =>
      new Set(
        filtered
          .filter((result) => result.forceExpandedForSearch)
          .map((result) => result.test.testId),
      ),
    [filtered],
  );
  const visibleTestIds = useMemo(
    () => new Set(filtered.map((result) => result.test.testId)),
    [filtered],
  );

  function isExpanded(test: DisplayTest): boolean {
    if (forceExpandedIds.has(test.testId)) {
      return true;
    }
    // undefined means no explicit choice yet - default to open only while genuinely RUNNING.
    return manualExpanded.get(test.testId) ?? test.status === "RUNNING";
  }

  function handleToggleExpand(test: DisplayTest): void {
    const currentlyExpanded = isExpanded(test);
    setManualExpanded((previous) => {
      const next = new Map(previous);
      next.set(test.testId, !currentlyExpanded);
      return next;
    });
  }

  function reveal(target: RunResultTarget): void {
    if (!visibleTestIds.has(target.testId)) {
      setFilter(DEFAULT_TEST_RESULTS_FILTER);
    }
    if (target.kind === "step") {
      // Persistent expand, as a manual toggle click would do, so the step's row actually mounts.
      setManualExpanded((previous) => {
        const next = new Map(previous);
        next.set(target.testId, true);
        return next;
      });
    }
    nextRequestIdRef.current += 1;
    setPendingReveal({ target, requestId: nextRequestIdRef.current });
  }

  // Re-checks on every render where `filtered` changes, to catch the render where the target
  // actually mounts (e.g. after reveal() resets the filter or force-expands the parent test).
  useEffect(() => {
    if (
      pendingReveal === undefined ||
      handledRequestIdRef.current === pendingReveal.requestId ||
      !visibleTestIds.has(pendingReveal.target.testId)
    ) {
      return;
    }
    const { target } = pendingReveal;
    const elementId =
      target.kind === "test"
        ? testRowElementId(target.testId)
        : stepRowElementId(target.testId, target.stepId);
    const row = document.getElementById(elementId);
    if (row === null) {
      return;
    }
    row.scrollIntoView({ block: "center" });
    row.focus();
    handledRequestIdRef.current = pendingReveal.requestId;
  }, [pendingReveal, visibleTestIds]);

  useImperativeHandle(ref, () => ({ reveal }));

  if (tests.length === 0) {
    return <EmptyState title="No tests started yet." />;
  }

  const firstVisibleProblem = filtered.find((result) =>
    PROBLEM_STATUSES.has(result.test.status),
  );

  return (
    <>
      <div className={styles.sectionHeaderRow}>
        <h2 className={styles.sectionTitle}>Tests</h2>
        {firstVisibleProblem !== undefined && (
          <button
            type="button"
            className={styles.linkButton}
            onClick={() =>
              reveal({ kind: "test", testId: firstVisibleProblem.test.testId })
            }
          >
            Jump to first failure
          </button>
        )}
      </div>
      <TestResultsFilters
        filter={filter}
        onFilterChange={setFilter}
        onClear={() => setFilter(DEFAULT_TEST_RESULTS_FILTER)}
        visibleCount={filtered.length}
        totalCount={tests.length}
      />
      {filtered.length === 0 ? (
        <EmptyState title="No tests match the current filters.">
          Clear filters above to see every test.
        </EmptyState>
      ) : (
        <TestResultsTable
          runId={runId}
          tests={filtered.map((result) => result.test)}
          isExpanded={isExpanded}
          onToggleExpand={handleToggleExpand}
          {...(artifactsErrorMessage !== undefined
            ? { artifactsErrorMessage }
            : {})}
        />
      )}
    </>
  );
});
