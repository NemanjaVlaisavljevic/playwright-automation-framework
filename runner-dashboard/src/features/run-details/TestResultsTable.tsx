import type { DisplayTest } from "./run-details-view-model";
import { TestResultRow } from "./TestResultRow";
import styles from "./RunDetailsPage.module.css";

export interface TestResultsTableProps {
  runId: string;
  /** Already filtered (by `TestResultsSection`) and in original `firstSequence` order - this
   * component never filters or sorts on its own. */
  tests: readonly DisplayTest[];
  artifactsErrorMessage?: string;
  isExpanded: (test: DisplayTest) => boolean;
  onToggleExpand: (test: DisplayTest) => void;
}

export function TestResultsTable({
  runId,
  tests,
  artifactsErrorMessage,
  isExpanded,
  onToggleExpand,
}: TestResultsTableProps) {
  return (
    <div className={styles.tableScroll}>
      <table className={styles.table}>
        <caption className="visually-hidden">Tests for run {runId}</caption>
        <thead>
          <tr>
            <th>Status</th>
            <th>Test</th>
            <th>Duration</th>
            <th>Detail</th>
            <th>Link</th>
          </tr>
        </thead>
        <tbody>
          {tests.map((test) => (
            <TestResultRow
              key={test.testId}
              runId={runId}
              test={test}
              expanded={isExpanded(test)}
              onToggleExpand={() => onToggleExpand(test)}
              {...(artifactsErrorMessage !== undefined
                ? { artifactsErrorMessage }
                : {})}
            />
          ))}
        </tbody>
      </table>
    </div>
  );
}
