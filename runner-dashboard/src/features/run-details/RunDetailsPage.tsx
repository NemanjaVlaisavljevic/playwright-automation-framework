import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { Link, useParams } from "react-router-dom";
import { cancelRun, getRun, listRunArtifacts } from "../../api/runner-api";
import { queryKeys } from "../../api/query-keys";
import { RunnerApiError } from "../../api/problem-detail";
import { Alert } from "../../components/ui/Alert";
import { Button } from "../../components/ui/Button";
import { cx } from "../../components/ui/cx";
import { LoadingSkeleton } from "../../components/ui/LoadingSkeleton";
import { PageHeader } from "../../components/ui/PageHeader";
import { runDurationMs } from "../../domain/duration";
import { isTerminalRunStatus } from "../../domain/run";
import type { EventStreamClient } from "../event-stream/event-stream-client";
import type { RunEventStreamStatus } from "../event-stream/run-event-reducer";
import {
  type ConnectionState,
  useRunEventStream,
} from "../event-stream/use-run-event-stream";
import { ArtifactsSection } from "./ArtifactsSection";
import { CopyRunIdButton } from "./CopyRunIdButton";
import { RunProgress } from "./RunProgress";
import { RunSummary } from "./RunSummary";
import { buildRunDetailsViewModel } from "./run-details-view-model";
import { TestResultsTable } from "./TestResultsTable";
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

  // The SSE stream itself already knows a run is over, and exactly when, the instant it processes
  // RUN_FINISHED - preferred over the REST `RunResponse` here so reconciliation (a test/step still
  // RUNNING once the run is terminal - see `run-details-view-model.ts`) does not depend on a REST
  // refetch succeeding or being fresh. `run.data` is the fallback for when the stream itself never
  // reached RUN_FINISHED (e.g. it broke into a permanent PROTOCOL_ERROR beforehand), which is
  // exactly the situation the REST-polling `refetchInterval` above exists to recover from anyway.
  const viewModel = buildRunDetailsViewModel({
    testsById: streamState.testsById,
    artifacts: artifacts.isSuccess ? artifacts.data : [],
    runStatus: streamState.runOutcome ?? run.data?.status,
    runFinishedAt: streamState.runFinishedAt ?? run.data?.finishedAt,
  });
  const tests = viewModel.tests;

  // There is still no live ARTIFACT_CREATED event (that remains a real future-phase concern - a
  // manifest write racing an in-flight listRunArtifacts response, precise per-artifact timing -
  // deliberately not solved here) - so this can't push a fresh capture the instant it's written.
  // But `AutomationExtension`'s `captureFailure` already runs (and finishes writing the manifest)
  // before the JUnit listener emits that same test's own terminal `TEST_*` event, so a `TEST_FAILED`
  // or `TEST_ABORTED` arriving over SSE is itself a reliable "this test's own artifacts, if any, are
  // now on disk" signal - good enough to stop making a viewer wait for the whole run to finish
  // before seeing a screenshot/trace for a test that already failed. Three distinct, non-exclusive
  // signals now trigger a refetch: the normal path (`RUN_FINISHED` arrived over SSE, `connectionState`
  // reaches `"CLOSED"`), the REST fallback path (a stream that broke before ever reaching
  // `RUN_FINISHED`, `runIsTerminal` instead), and this early per-test-failure path. Kept as separate
  // effects, each gated on its own path being the one actually responsible - `connectionState`
  // trivially implies `runIsTerminal` will *also* eventually become true once the resulting
  // invalidate's refetch resolves, and a single combined effect would then fire the same
  // invalidation twice (once from each signal) for the one normal-path finish, wasting a request.
  const failedOrAbortedTestCount = tests.filter(
    (test) => test.status === "FAILED" || test.status === "ABORTED",
  ).length;
  useEffect(() => {
    if (failedOrAbortedTestCount > 0) {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.runArtifacts(runId),
      });
    }
  }, [failedOrAbortedTestCount, runId, queryClient]);
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
          <>
            <CopyRunIdButton runId={runId} />
            {canCancel && (
              <Button
                variant="danger"
                onClick={() => cancel.mutate()}
                disabled={cancel.isPending}
              >
                Cancel
              </Button>
            )}
          </>
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
        <RunSummary
          run={run.data}
          runDuration={runDuration}
          cancelErrorMessage={
            cancel.isError ? describeApiError(cancel.error) : undefined
          }
          hasIntegrityWarning={viewModel.hasIncompleteTestsDespiteSucceededRun}
        />
      )}

      <RunProgress
        totalCount={tests.length}
        completedCount={viewModel.completedCount}
        counts={viewModel.counts}
      />

      <div className={styles.section}>
        <h2 className={styles.sectionTitle}>Tests</h2>
        <TestResultsTable runId={runId} tests={tests} />
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
          <ArtifactsSection runId={runId} artifacts={artifacts.data} />
        </div>
      )}
    </>
  );
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
