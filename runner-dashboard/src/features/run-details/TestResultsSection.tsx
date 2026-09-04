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
   * Reveals, scrolls to, and focuses the given test or step's own row - resetting the active
   * filters first if (and only if) they're currently hiding the target test, and (for a step
   * target only) force-expanding its parent test exactly as a real manual click would, so the
   * user can still close it again afterward. `LiveFocusPanel`'s click callback and C4.5's deep
   * links both call this - the one reveal mechanism, not a second, parallel one per caller.
   */
  reveal(target: RunResultTarget): void;
}

export interface TestResultsSectionProps {
  runId: string;
  tests: readonly DisplayTest[];
  artifactsErrorMessage?: string;
}

/**
 * Owns everything about *how* the Tests table is currently shown - the C4.4 search/status/evidence
 * filter, each row's manual expand/collapse choice, and reveal-on-demand navigation - so
 * `RunDetailsPage.tsx` stays a plain data orchestrator (fetch/subscribe, hand `tests` down) rather
 * than also wiring together every UI detail of this one section itself.
 *
 * Filtering is display-only: it is computed fresh from `tests` on every render via
 * `filterTestResults` (a pure function, see `test-results-filter.ts`) and never touches the SSE
 * reducer, `RunDetailsViewModel`, `Progress`, or `LiveFocusPanel` - those three always see the full,
 * unfiltered `tests` list, exactly as the C4.4 spec requires.
 */
export const TestResultsSection = forwardRef<
  TestResultsSectionHandle,
  TestResultsSectionProps
>(function TestResultsSection({ runId, tests, artifactsErrorMessage }, ref) {
  const [filter, setFilter] = useState<TestResultsFilter>(
    DEFAULT_TEST_RESULTS_FILTER,
  );
  // Keyed by testId, not owned per-row: a filtered-out row unmounts, so keeping this here (rather
  // than in `TestResultRow` itself, as it lived before C4.4) is what lets a user's explicit choice
  // survive the row disappearing and reappearing as filters change.
  const [manualExpanded, setManualExpanded] = useState<
    ReadonlyMap<string, boolean>
  >(new Map());
  // The reveal currently trying to bring its target on-screen (state, so a call to `reveal` always
  // triggers a render, even when the filter itself doesn't need to change). Each call gets its own
  // monotonic `requestId` - deliberately *not* deduplicated by the target itself: a user clicking
  // the same Live Focus item (or "Jump to first failure") twice in a row, after scrolling away in
  // between, is a fresh request each time and must scroll/focus again - a real review finding, since
  // keying this by target alone (as an earlier version did) made every reveal after the first one
  // for the same target silently do nothing. `handledRequestIdRef` (a ref, not more state) is what
  // stops the effect below from re-running the scroll/focus for the *same* still-pending request on
  // an unrelated re-render (e.g. a new SSE event), without ever needing to call `setState` itself.
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
    // `undefined` means the user hasn't explicitly chosen yet - default to open only while the
    // test is still (genuinely) RUNNING; see the same reasoning `TestResultRow` used to carry
    // itself before this state moved up here.
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
      // Real, persistent expand - exactly what a manual click on the toggle would do - so the
      // step's own row actually mounts, and the user can still collapse it again afterward (this
      // is not a temporary search-style overlay).
      setManualExpanded((previous) => {
        const next = new Map(previous);
        next.set(target.testId, true);
        return next;
      });
    }
    nextRequestIdRef.current += 1;
    setPendingReveal({ target, requestId: nextRequestIdRef.current });
  }

  // `visibleTestIds` changes reference whenever `filtered` is recomputed (any SSE-driven `tests`
  // change, or a filter change), so listing it here means this effect re-checks on every such
  // render while a reveal is pending - exactly what's needed to catch the very next render where
  // the target actually mounts (e.g. right after `reveal` reset the filter or force-expanded the
  // parent test above; a step target may also need one more render for that expand to actually
  // paint the DOM - the `document.getElementById` check below simply waits for that too, rather
  // than tracking expand-completion separately). No `setState` call in the body:
  // `handledRequestIdRef` (not more state) is what stops it from re-running the scroll/focus for the
  // same still-pending *request* once it has already been handled.
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
