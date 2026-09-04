import {
  type EvidenceFilter,
  type TestResultsFilter,
  type TestStatusFilter,
} from "./test-results-filter";
import styles from "./RunDetailsPage.module.css";

/**
 * Fixed, not derived from whichever statuses happen to be present in the current run - a filter
 * option must never disappear just because, say, the last `RUNNING` test finished, or a viewer
 * could lose their own selection mid-run without ever touching it themselves.
 */
const STATUS_OPTIONS: ReadonlyArray<{
  readonly value: TestStatusFilter;
  readonly label: string;
}> = [
  { value: "ALL", label: "All statuses" },
  { value: "PROBLEMS", label: "Problems" },
  { value: "RUNNING", label: "Running" },
  { value: "PASSED", label: "Passed" },
  { value: "FAILED", label: "Failed" },
  { value: "ABORTED", label: "Aborted" },
  { value: "SKIPPED", label: "Skipped" },
  { value: "INTERRUPTED", label: "Interrupted" },
];

const EVIDENCE_OPTIONS: ReadonlyArray<{
  readonly value: EvidenceFilter;
  readonly label: string;
}> = [
  { value: "ALL", label: "All evidence" },
  { value: "HAS_ARTIFACTS", label: "Has artifacts" },
  { value: "NO_ARTIFACTS", label: "No artifacts" },
];

export interface TestResultsFiltersProps {
  filter: TestResultsFilter;
  onFilterChange: (filter: TestResultsFilter) => void;
  onClear: () => void;
  visibleCount: number;
  totalCount: number;
}

export function TestResultsFilters({
  filter,
  onFilterChange,
  onClear,
  visibleCount,
  totalCount,
}: TestResultsFiltersProps) {
  return (
    <div className={styles.filtersBar}>
      <div className={styles.filterField}>
        <label htmlFor="test-results-search">Search tests or steps</label>
        <input
          id="test-results-search"
          type="text"
          value={filter.search}
          onChange={(event) =>
            onFilterChange({ ...filter, search: event.target.value })
          }
        />
      </div>
      <div className={styles.filterField}>
        <label htmlFor="test-results-status">Status</label>
        <select
          id="test-results-status"
          value={filter.status}
          onChange={(event) =>
            onFilterChange({
              ...filter,
              status: event.target.value as TestStatusFilter,
            })
          }
        >
          {STATUS_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </div>
      <div className={styles.filterField}>
        <label htmlFor="test-results-evidence">Evidence</label>
        <select
          id="test-results-evidence"
          value={filter.evidence}
          onChange={(event) =>
            onFilterChange({
              ...filter,
              evidence: event.target.value as EvidenceFilter,
            })
          }
        >
          {EVIDENCE_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </div>
      <button type="button" className={styles.copyButton} onClick={onClear}>
        Clear filters
      </button>
      {/* Visible count, and `aria-live="polite"` on the same element so a screen reader also hears
          it update on every filter change - one element serving both, not a separate hidden copy. */}
      <p className={styles.filterCount} aria-live="polite">
        {`Showing ${visibleCount} of ${totalCount} tests.`}
      </p>
    </div>
  );
}
