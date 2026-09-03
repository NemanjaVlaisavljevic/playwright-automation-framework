import { MetricCard } from "../../components/ui/MetricCard";
import { ProgressBar } from "../../components/ui/ProgressBar";
import type { DisplayTestStatus } from "./run-details-view-model";
import styles from "./RunDetailsPage.module.css";

export interface RunProgressProps {
  totalCount: number;
  completedCount: number;
  counts: Record<DisplayTestStatus, number>;
}

export function RunProgress({
  totalCount,
  completedCount,
  counts,
}: RunProgressProps) {
  return (
    <div className={styles.section}>
      <h2 className={styles.sectionTitle}>Progress</h2>
      <div className={styles.metrics}>
        <MetricCard label="Total" value={totalCount} />
        <MetricCard label="Running" value={counts.RUNNING} tone="info" />
        <MetricCard label="Passed" value={counts.PASSED} tone="success" />
        <MetricCard label="Failed" value={counts.FAILED} tone="danger" />
        <MetricCard label="Skipped" value={counts.SKIPPED} />
        <MetricCard label="Aborted" value={counts.ABORTED} tone="warning" />
        <MetricCard
          label="Interrupted"
          value={counts.INTERRUPTED}
          tone="warning"
        />
      </div>
      {totalCount > 0 && (
        <div className={styles.progress}>
          <ProgressBar
            value={(completedCount / totalCount) * 100}
            label={`${completedCount} of ${totalCount} tests complete`}
          />
        </div>
      )}
    </div>
  );
}
