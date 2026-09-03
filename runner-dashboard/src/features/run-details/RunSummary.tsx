import type { RunResponse } from "../../api/runner-api";
import { Alert } from "../../components/ui/Alert";
import { StatusBadge } from "../../components/ui/StatusBadge";
import { formatLocalDateTime } from "../../domain/datetime";
import { formatDuration } from "../../domain/duration";
import styles from "./RunDetailsPage.module.css";

export interface RunSummaryProps {
  run: RunResponse;
  runDuration: number | undefined;
  cancelErrorMessage: string | undefined;
  /** See `RunDetailsViewModel.hasIncompleteTestsDespiteSucceededRun`'s own doc comment. */
  hasIntegrityWarning: boolean;
}

export function RunSummary({
  run,
  runDuration,
  cancelErrorMessage,
  hasIntegrityWarning,
}: RunSummaryProps) {
  return (
    <div className={styles.section}>
      <dl className={styles.details}>
        <dt>Status</dt>
        <dd className={styles.statusValue}>
          <StatusBadge status={run.status} />
        </dd>
        <dt>Suite</dt>
        <dd>{run.suite}</dd>
        <dt>Environment</dt>
        <dd>{run.environment}</dd>
        <dt>Requested</dt>
        <dd>{formatLocalDateTime(run.requestedAt)}</dd>
        {run.finishedAt !== undefined && (
          <>
            <dt>Finished</dt>
            <dd>{formatLocalDateTime(run.finishedAt)}</dd>
          </>
        )}
        {runDuration !== undefined && (
          <>
            <dt>Duration</dt>
            <dd>{formatDuration(runDuration)}</dd>
          </>
        )}
      </dl>
      {hasIntegrityWarning && (
        <Alert tone="warning">
          This run reports SUCCEEDED, but at least one test or step never
          reported its own terminal result - the event stream may be incomplete.
        </Alert>
      )}
      {cancelErrorMessage !== undefined && (
        <Alert>Could not cancel run: {cancelErrorMessage}</Alert>
      )}
      {run.startedAt !== undefined && (
        <a className={styles.downloadLink} href={run.processLogUrl}>
          Download log
        </a>
      )}
    </div>
  );
}
