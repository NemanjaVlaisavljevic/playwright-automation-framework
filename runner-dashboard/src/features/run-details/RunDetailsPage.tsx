import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { Link, useParams } from "react-router-dom";
import {
  cancelRun,
  getRun,
  listRunArtifacts,
  type ArtifactSummaryResponse,
} from "../../api/runner-api";
import { queryKeys } from "../../api/query-keys";
import { RunnerApiError } from "../../api/problem-detail";
import { Alert } from "../../components/ui/Alert";
import { Button } from "../../components/ui/Button";
import { cx } from "../../components/ui/cx";
import { EmptyState } from "../../components/ui/EmptyState";
import { LoadingSkeleton } from "../../components/ui/LoadingSkeleton";
import { MetricCard } from "../../components/ui/MetricCard";
import { PageHeader } from "../../components/ui/PageHeader";
import { ProgressBar } from "../../components/ui/ProgressBar";
import { StatusBadge } from "../../components/ui/StatusBadge";
import { formatBytes } from "../../domain/bytes";
import { formatLocalDateTime } from "../../domain/datetime";
import { formatDuration, runDurationMs } from "../../domain/duration";
import { isTerminalRunStatus } from "../../domain/run";
import type { EventStreamClient } from "../event-stream/event-stream-client";
import type {
  RunEventStreamStatus,
  TestExecution,
  TestExecutionStatus,
} from "../event-stream/run-event-reducer";
import {
  type ConnectionState,
  useRunEventStream,
} from "../event-stream/use-run-event-stream";
import styles from "./RunDetailsPage.module.css";

export interface RunDetailsPageProps {
  /** Overridable for tests - see `RunDetailsPage.test.tsx`. Production code never passes this. */
  eventStreamClient?: EventStreamClient;
  /** Overridable for tests - see `RunDetailsPage.test.tsx`. Production code never passes this. */
  runPollIntervalMs?: number;
}

export function RunDetailsPage({
  eventStreamClient,
  runPollIntervalMs,
}: RunDetailsPageProps = {}) {
  const { runId } = useParams<{ runId: string }>();
  if (runId === undefined) {
    return <Alert>No run ID in the URL.</Alert>;
  }
  return (
    <RunDetails
      // `key={runId}` forces a full remount (and thus fresh `useRunEventStream` state) when
      // navigating from one run's details page directly to another's without an intervening
      // unmount - see `use-run-event-stream.ts`'s doc comment on why it doesn't reset itself.
      key={runId}
      runId={runId}
      {...(eventStreamClient !== undefined ? { eventStreamClient } : {})}
      {...(runPollIntervalMs !== undefined ? { runPollIntervalMs } : {})}
    />
  );
}

function RunDetails({
  runId,
  eventStreamClient,
  runPollIntervalMs = 3000,
}: {
  runId: string;
  eventStreamClient?: EventStreamClient;
  runPollIntervalMs?: number;
}) {
  const { connectionState, streamState } = useRunEventStream(
    runId,
    eventStreamClient,
  );

  const run = useQuery({
    queryKey: queryKeys.run(runId),
    queryFn: () => getRun(runId),
    // `useRunEventStream` invalidates `run` exactly once when the stream freezes for any reason
    // (see that hook's own doc comment), including a permanent `PROTOCOL_ERROR` - but if the run
    // hasn't reached a terminal status by that one refetch, nothing else was left to ever refetch
    // it again: a review caught that this stranded both the header's status and the Artifacts
    // section (see below) once a run's SSE stream broke before `RUN_FINISHED`. Falling back to
    // plain REST polling here - only while the stream can no longer be trusted, and only until the
    // status this same fallback reads back is itself terminal - recovers both.
    refetchInterval: (query) => {
      if (connectionState !== "PROTOCOL_ERROR") {
        return false;
      }
      const status = query.state.data?.status;
      if (status !== undefined && isTerminalRunStatus(status)) {
        return false;
      }
      // A definitive 404 (not a transient network/5xx blip) means the run is gone for good - the
      // runner service only keeps run history in memory (see docs/SSE_CONTRACT_V1.md), so a
      // restart between polls is a real, permanent case, not something retrying will ever fix.
      // `query.state.data` would otherwise keep whatever the last *successful* response was (still
      // RUNNING, say) forever, since a failed refetch doesn't clear it - polling a run that will
      // never come back again is exactly the bug a review caught here.
      const error = query.state.error;
      if (
        error instanceof RunnerApiError &&
        error.kind === "http" &&
        error.status === 404
      ) {
        return false;
      }
      return runPollIntervalMs;
    },
    refetchIntervalInBackground: true,
  });

  const queryClient = useQueryClient();
  const cancel = useMutation({
    mutationFn: () => cancelRun(runId),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: queryKeys.run(runId) }),
  });

  const artifacts = useQuery({
    queryKey: queryKeys.runArtifacts(runId),
    queryFn: () => listRunArtifacts(runId),
    // Gated on the run lookup itself succeeding: the backend 404s this endpoint too when the run
    // doesn't exist, and firing it anyway would show a second, redundant "not available" error
    // alongside the run's own - a real duplicate-text failure an E2E test caught (both errors
    // share `describeApiError`'s 404 message, which made `getByText(...)` match twice).
    enabled: run.isSuccess,
  });
  const runIsTerminal = run.isSuccess && isTerminalRunStatus(run.data.status);
  // Faza A ships REST-only artifact retrieval, with no ARTIFACT_CREATED live event yet (deferred to
  // the Step API / Event V2 phase) - so a fresh capture during a still-running test can't be pushed
  // to the dashboard. Two distinct, non-exclusive signals both mean "every artifact for this run is
  // now guaranteed to have been captured and manifested" - the normal path (`RUN_FINISHED` arrived
  // over SSE, `connectionState` reaches `"CLOSED"`) and the REST fallback path above (a stream that
  // broke before ever reaching `RUN_FINISHED`, `runIsTerminal` instead). Kept as two separate
  // effects, each gated on its own path being the one actually responsible - `connectionState`
  // trivially implies `runIsTerminal` will *also* eventually become true once the resulting
  // invalidate's refetch resolves, and a single combined effect would then fire the same
  // invalidation twice (once from each signal) for the one normal-path finish, wasting a request.
  useEffect(() => {
    if (connectionState === "CLOSED") {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.runArtifacts(runId),
      });
    }
  }, [connectionState, runId, queryClient]);
  useEffect(() => {
    if (connectionState === "PROTOCOL_ERROR" && runIsTerminal) {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.runArtifacts(runId),
      });
    }
  }, [connectionState, runIsTerminal, runId, queryClient]);

  const tests = Array.from(streamState.testsById.values()).sort(
    (a, b) => a.firstSequence - b.firstSequence,
  );
  const counts = countByStatus(tests.map((test) => test.status));
  const completed = tests.length - counts.RUNNING;
  const runDuration = run.isSuccess ? runDurationMs(run.data) : undefined;

  const canCancel = run.isSuccess && !isTerminalRunStatus(run.data.status);

  return (
    <>
      <PageHeader
        title={
          <>
            Run <code>{runId}</code>
          </>
        }
        actions={
          canCancel ? (
            <Button
              variant="danger"
              onClick={() => cancel.mutate()}
              disabled={cancel.isPending}
            >
              Cancel
            </Button>
          ) : undefined
        }
      />

      <div
        role="status"
        className={cx(
          styles.connectionBanner,
          styles[connectionTone(connectionState)],
        )}
      >
        {describeConnectionState(connectionState, streamState.status)}
      </div>
      {connectionState === "PROTOCOL_ERROR" && (
        <p className={styles.backLink}>
          <Link to="/runs">Back to runs</Link>
        </p>
      )}

      {run.isPending && (
        <div className={styles.section}>
          <LoadingSkeleton lines={4} />
        </div>
      )}
      {run.isError && (
        <div className={styles.section}>
          <Alert>Could not load run: {describeApiError(run.error)}</Alert>
        </div>
      )}
      {run.isSuccess && (
        <div className={styles.section}>
          <dl className={styles.details}>
            <dt>Status</dt>
            <dd>
              <StatusBadge status={run.data.status} />
            </dd>
            <dt>Suite</dt>
            <dd>{run.data.suite}</dd>
            <dt>Environment</dt>
            <dd>{run.data.environment}</dd>
            <dt>Requested</dt>
            <dd>{formatLocalDateTime(run.data.requestedAt)}</dd>
            {run.data.finishedAt !== undefined && (
              <>
                <dt>Finished</dt>
                <dd>{formatLocalDateTime(run.data.finishedAt)}</dd>
              </>
            )}
            {runDuration !== undefined && (
              <>
                <dt>Duration</dt>
                <dd>{formatDuration(runDuration)}</dd>
              </>
            )}
          </dl>
          {cancel.isError && (
            <Alert>
              Could not cancel run: {describeApiError(cancel.error)}
            </Alert>
          )}
          {run.data.startedAt !== undefined && (
            <a className={styles.downloadLink} href={run.data.processLogUrl}>
              Download log
            </a>
          )}
        </div>
      )}

      <div className={styles.section}>
        <h2 className={styles.sectionTitle}>Progress</h2>
        <div className={styles.metrics}>
          <MetricCard label="Total" value={tests.length} />
          <MetricCard label="Running" value={counts.RUNNING} tone="info" />
          <MetricCard label="Passed" value={counts.PASSED} tone="success" />
          <MetricCard label="Failed" value={counts.FAILED} tone="danger" />
          <MetricCard label="Skipped" value={counts.SKIPPED} />
          <MetricCard label="Aborted" value={counts.ABORTED} tone="warning" />
        </div>
        {tests.length > 0 && (
          <div className={styles.progress}>
            <ProgressBar
              value={(completed / tests.length) * 100}
              label={`${completed} of ${tests.length} tests complete`}
            />
          </div>
        )}
      </div>

      <div className={styles.section}>
        <h2 className={styles.sectionTitle}>Tests</h2>
        {tests.length === 0 ? (
          <EmptyState title="No tests started yet." />
        ) : (
          <div className={styles.tableScroll}>
            <table className={styles.table}>
              <caption className="visually-hidden">
                Tests for run {runId}
              </caption>
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
                  <TestRow key={test.testId} test={test} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {artifacts.isError && (
        <div className={styles.section}>
          <Alert>
            Could not load artifacts: {describeApiError(artifacts.error)}
          </Alert>
        </div>
      )}
      {artifacts.isSuccess && artifacts.data.length > 0 && (
        <div className={styles.section}>
          <h2 className={styles.sectionTitle}>Artifacts</h2>
          <div className={styles.tableScroll}>
            <table className={styles.table}>
              <caption className="visually-hidden">
                Artifacts for run {runId}
              </caption>
              <thead>
                <tr>
                  <th>Test</th>
                  <th>Type</th>
                  <th>Size</th>
                  <th>Artifact</th>
                </tr>
              </thead>
              <tbody>
                {artifacts.data.map((artifact) => (
                  <ArtifactRow key={artifact.artifactId} artifact={artifact} />
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </>
  );
}

function ArtifactRow({ artifact }: { artifact: ArtifactSummaryResponse }) {
  return (
    <tr>
      <td>{artifact.testDisplayName}</td>
      <td>{artifactTypeLabel(artifact.type)}</td>
      <td>{formatBytes(artifact.sizeBytes)}</td>
      <td>
        {artifact.type === "SCREENSHOT" ? (
          <a href={artifact.downloadUrl} target="_blank" rel="noreferrer">
            <img
              src={artifact.downloadUrl}
              alt={`Screenshot for ${artifact.testDisplayName}`}
              className={styles.artifactThumbnail}
              loading="lazy"
              decoding="async"
            />
          </a>
        ) : (
          <a className={styles.downloadLink} href={artifact.downloadUrl}>
            Download {artifactTypeLabel(artifact.type).toLowerCase()}
          </a>
        )}
      </td>
    </tr>
  );
}

function artifactTypeLabel(type: ArtifactSummaryResponse["type"]): string {
  switch (type) {
    case "SCREENSHOT":
      return "Screenshot";
    case "TRACE":
      return "Trace";
    case "VIDEO":
      return "Video";
  }
}

function TestRow({ test }: { test: TestExecution }) {
  const durationMs = runDurationMs(test);
  return (
    <tr className={test.status === "FAILED" ? styles.failedRow : undefined}>
      <td>
        <StatusBadge status={test.status} />
      </td>
      <td>{test.testDisplayName}</td>
      <td>{durationMs !== undefined ? formatDuration(durationMs) : "—"}</td>
      <td>
        {test.detail !== undefined ? (
          <details>
            <summary>Detail</summary>
            <pre className={styles.detailText}>{test.detail}</pre>
          </details>
        ) : (
          "—"
        )}
      </td>
    </tr>
  );
}

function countByStatus(
  statuses: TestExecutionStatus[],
): Record<TestExecutionStatus, number> {
  const counts: Record<TestExecutionStatus, number> = {
    RUNNING: 0,
    PASSED: 0,
    FAILED: 0,
    ABORTED: 0,
    SKIPPED: 0,
  };
  for (const status of statuses) {
    counts[status] += 1;
  }
  return counts;
}

function connectionTone(
  state: ConnectionState,
): "neutral" | "success" | "warning" | "danger" {
  switch (state) {
    case "CONNECTING":
      return "neutral";
    case "LIVE":
      return "success";
    case "RECONNECTING":
    case "RECOVERING":
      return "warning";
    case "PROTOCOL_ERROR":
      return "danger";
    case "CLOSED":
      return "neutral";
  }
}

function describeConnectionState(
  state: ConnectionState,
  status: RunEventStreamStatus,
): string {
  switch (state) {
    case "CONNECTING":
      return "Connecting to live results…";
    case "LIVE":
      return "Live";
    case "RECONNECTING":
      return "Connection lost — reconnecting…";
    case "RECOVERING":
      return "Live stream fell out of sync. Replaying from the beginning…";
    case "PROTOCOL_ERROR":
      return describeProtocolError(status);
    case "CLOSED":
      return "Run finished.";
  }
}

/**
 * `PROTOCOL_ERROR` covers three distinct reducer statuses (see `use-run-event-stream.ts`'s own
 * gap-retry logic) - a gap that couldn't recover even after one fresh-replay attempt reads
 * differently to a user than a genuine contract violation, so this branches on the reducer's own
 * `status.kind` rather than showing one generic message for all three.
 */
function describeProtocolError(status: RunEventStreamStatus): string {
  switch (status.kind) {
    case "compatibility-error":
      return `This dashboard doesn't support event schema version "${status.receivedSchemaVersion}" - reload after the dashboard is updated.`;
    case "gap":
      return "Live stream lost sync twice and could not recover automatically.";
    default:
      return "Event stream reported a protocol violation and was closed.";
  }
}

function describeApiError(error: unknown): string {
  if (
    error instanceof RunnerApiError &&
    error.kind === "http" &&
    error.status === 404
  ) {
    return "This run is no longer available - the runner service may have restarted (run history is in-memory only, see docs/SSE_CONTRACT_V1.md).";
  }
  return error instanceof RunnerApiError ? error.message : "Unknown error";
}
