import { useQuery } from "@tanstack/react-query";
import { getPerformanceBaseline } from "../../api/performance-baseline-api";
import { queryKeys } from "../../api/query-keys";
import { RunnerApiError } from "../../api/problem-detail";
import { Alert } from "../../components/ui/Alert";
import { LoadingSkeleton } from "../../components/ui/LoadingSkeleton";
import { MetricCard } from "../../components/ui/MetricCard";
import { PageHeader } from "../../components/ui/PageHeader";
import { formatLocalDateTime } from "../../domain/datetime";
import { ScenarioSection } from "./ScenarioSection";
import styles from "./PerformancePage.module.css";

/**
 * Renders a committed, manually-published snapshot of one CI run's k6 results (see
 * `performance/baselines/<run-id>/`). Not live monitoring - nothing here polls.
 */
export function PerformancePage() {
  const baseline = useQuery({
    queryKey: queryKeys.performanceBaseline,
    queryFn: getPerformanceBaseline,
  });

  const regressedScenarioCount =
    baseline.data?.scenarios.filter(
      (scenario) => scenario.status === "REGRESSION",
    ).length ?? 0;

  return (
    <>
      <PageHeader title="Performance" />
      {/* Plain callout, not `Alert`: role="alert" is for errors/urgent changes, not static text. */}
      <aside className={styles.disclaimer}>
        <span aria-hidden="true">ℹ</span> CI performance snapshot - not live
        monitoring, no SLA. Every number below is from one archived GitHub
        Actions run, refreshed only when a new baseline is deliberately
        published.
      </aside>

      {baseline.isPending && (
        <div className={styles.section}>
          <LoadingSkeleton lines={6} />
        </div>
      )}

      {baseline.isError && (
        <div className={styles.section}>
          <Alert>
            Could not load the performance baseline:{" "}
            {describeError(baseline.error)}
          </Alert>
        </div>
      )}

      {baseline.isSuccess && (
        <>
          <div className={styles.metrics}>
            <MetricCard
              label="Scenarios"
              value={baseline.data.scenarios.length}
            />
            <MetricCard
              label="Regressed scenarios"
              value={regressedScenarioCount}
              tone={regressedScenarioCount > 0 ? "danger" : "success"}
            />
            <MetricCard label="Profile" value={baseline.data.source.profile} />
          </div>

          <div className={styles.sourceSection}>
            <h2 className={styles.sectionTitle}>Source</h2>
            <dl className={styles.details}>
              <dt>Commit</dt>
              <dd>{baseline.data.source.commitSha.slice(0, 7)}</dd>
              <dt>Measured</dt>
              <dd>{formatLocalDateTime(baseline.data.source.measuredAt)}</dd>
              <dt>Published</dt>
              <dd>{formatLocalDateTime(baseline.data.generatedAt)}</dd>
              <dt>Execution mode</dt>
              <dd>{baseline.data.source.executionMode}</dd>
              <dt>Repetitions</dt>
              <dd>{baseline.data.source.repetitions}</dd>
              <dt>Runner image</dt>
              <dd>{baseline.data.source.runnerImage}</dd>
              <dt>k6 version</dt>
              <dd>{baseline.data.source.k6Version}</dd>
              <dt>Actions run</dt>
              <dd>
                <a href={baseline.data.source.workflowRun.url}>
                  Run #{baseline.data.source.workflowRun.id} (attempt{" "}
                  {baseline.data.source.workflowRun.attempt})
                </a>
              </dd>
            </dl>
          </div>

          {baseline.data.scenarios.map((scenario) => (
            <ScenarioSection key={scenario.scenarioId} scenario={scenario} />
          ))}
        </>
      )}
    </>
  );
}

function describeError(error: unknown): string {
  if (!(error instanceof RunnerApiError)) {
    return "Unknown error";
  }
  if (error.kind === "network") {
    return "Could not reach the server.";
  }
  return error.message;
}
