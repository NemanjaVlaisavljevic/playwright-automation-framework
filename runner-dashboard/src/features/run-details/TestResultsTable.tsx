import { EmptyState } from "../../components/ui/EmptyState";
import type { DisplayTest } from "./run-details-view-model";
import { TestResultRow } from "./TestResultRow";
import styles from "./RunDetailsPage.module.css";

export function TestResultsTable({
  runId,
  tests,
}: {
  runId: string;
  tests: readonly DisplayTest[];
}) {
  if (tests.length === 0) {
    return <EmptyState title="No tests started yet." />;
  }
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
          </tr>
        </thead>
        <tbody>
          {tests.map((test) => (
            <TestResultRow key={test.testId} test={test} />
          ))}
        </tbody>
      </table>
    </div>
  );
}
