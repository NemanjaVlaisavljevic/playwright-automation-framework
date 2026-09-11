import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useRef } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { cancelRun, getRun, listRunArtifacts } from "../../api/runner-api";
import { queryKeys } from "../../api/query-keys";
import {
  RunnerApiError,
  describePermissionError,
} from "../../api/problem-detail";
import { Alert } from "../../components/ui/Alert";
import { Button } from "../../components/ui/Button";
import { cx } from "../../components/ui/cx";
import { LoadingSkeleton } from "../../components/ui/LoadingSkeleton";
import { PageHeader } from "../../components/ui/PageHeader";
import { useCanManageRuns } from "../auth/useCanManageRuns";
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
import { LiveFocusPanel } from "./LiveFocusPanel";
import { RunProgress } from "./RunProgress";
import { RunSummary } from "./RunSummary";
import { buildRunDetailsViewModel } from "./run-details-view-model";
import {
  computeDeepLinkStatus,
  describeDeepLinkStatus,
  parseRunResultTarget,
  runResultTargetKey,
} from "./run-result-target";
import {
  TestResultsSection,
  type TestResultsSectionHandle,
} from "./TestResultsSection";
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
      // Forces a full remount (fresh useRunEventStream state) when navigating between runs.
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
  const testResultsRef = useRef<TestResultsSectionHandle>(null);
  // The URL (`?testId=&stepId=`) is only ever the initial target, not continuously-synced state:
  // parsed once per render, and an already-revealed target is never re-revealed for the same query string.
  const [searchParams] = useSearchParams();
  const parsedDeepLink = parseRunResultTarget(searchParams);
  const deepLinkTarget =
    parsedDeepLink.kind === "valid" ? parsedDeepLink.target : undefined;
  const deepLinkHandledKeyRef = useRef<string | undefined>(undefined);
  // A target that disappears from the URL must be treated as gone, not "handled forever" - otherwise
  // Back/Forward to the same target would stay stuck refusing to reveal it again.
  useEffect(() => {
    if (deepLinkTarget === undefined) {
      deepLinkHandledKeyRef.current = undefined;
    }
  }, [deepLinkTarget]);

  const run = useQuery({
    queryKey: queryKeys.run(runId),
    queryFn: () => getRun(runId),
    // `useRunEventStream` invalidates `run` once when the stream freezes, but if the run isn't
    // terminal by that refetch, nothing refetches it again. Falls back to REST polling only while
    // the stream is untrusted (PROTOCOL_ERROR) and until the polled status is itself terminal.
    refetchInterval: (query) => {
      if (connectionState !== "PROTOCOL_ERROR") {
        return false;
      }
      const status = query.state.data?.status;
      if (status !== undefined && isTerminalRunStatus(status)) {
        return false;
      }
      // A definitive 404 means the run is gone for good (in-memory run history, see
      // docs/SSE_CONTRACT_V1.md) - retrying would poll forever since a failed refetch doesn't
      // clear the last successful data.
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
    // Gated on the run lookup succeeding - the backend 404s this endpoint too, so firing it
    // anyway would duplicate the run's own "not available" error.
    enabled: run.isSuccess,
  });
  const runIsTerminal = run.isSuccess && isTerminalRunStatus(run.data.status);

  // Prefers the SSE stream's own RUN_FINISHED signal over REST so reconciliation doesn't depend
  // on a fresh refetch; `run.data` is the fallback if the stream never reached RUN_FINISHED.
  const viewModel = buildRunDetailsViewModel({
    testsById: streamState.testsById,
    artifacts: artifacts.isSuccess ? artifacts.data : [],
    runStatus: streamState.runOutcome ?? run.data?.status,
    runFinishedAt: streamState.runFinishedAt ?? run.data?.finishedAt,
  });
  const tests = viewModel.tests;

  // Same SSE-first, REST-fallback preference as above, so LiveFocusPanel doesn't keep showing
  // itself just because the live connection alone hasn't reached CLOSED yet.
  const overallRunStatus = streamState.runOutcome ?? run.data?.status;
  const deepLinkStatus = useMemo(
    () =>
      parsedDeepLink.kind === "invalid"
        ? ({ kind: "invalid" } as const)
        : computeDeepLinkStatus(deepLinkTarget, tests, connectionState),
    [parsedDeepLink.kind, deepLinkTarget, tests, connectionState],
  );
  // Fires at most once per distinct target - a later re-render with the same "found" target
  // must not call reveal() again.
  useEffect(() => {
    if (deepLinkTarget === undefined || deepLinkStatus.kind !== "found") {
      return;
    }
    const key = runResultTargetKey(deepLinkTarget);
    if (deepLinkHandledKeyRef.current === key) {
      return;
    }
    testResultsRef.current?.reveal(deepLinkTarget);
    deepLinkHandledKeyRef.current = key;
  }, [deepLinkTarget, deepLinkStatus]);

  // No live ARTIFACT_CREATED event exists yet, but captureFailure finishes writing a test's
  // manifest before its terminal TEST_* event fires - so a TEST_FAILED/ABORTED over SSE reliably
  // means that test's artifacts are already on disk, and refetching early avoids waiting for the
  // whole run to finish. Three separate effects (each own signal: SSE close, REST-fallback
  // terminal, early failure) avoid double-firing the same invalidation for one normal finish.
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

  const canManageRuns = useCanManageRuns();
  const runDuration = run.isSuccess ? runDurationMs(run.data) : undefined;
  const canCancel =
    run.isSuccess && !isTerminalRunStatus(run.data.status) && canManageRuns;
  // Scoped to each failing test's failure panel, not just the generic banner below.
  const artifactsErrorMessage = artifacts.isError
    ? describeApiError(artifacts.error)
    : undefined;

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

      <LiveFocusPanel
        tests={tests}
        connectionState={connectionState}
        runStatus={overallRunStatus}
        onSelectTest={(testId) =>
          testResultsRef.current?.reveal({ kind: "test", testId })
        }
      />

      {deepLinkStatus.kind === "waiting" && (
        <p className="visually-hidden" aria-live="polite">
          {describeDeepLinkStatus(deepLinkStatus)}
        </p>
      )}
      {(deepLinkStatus.kind === "invalid" ||
        deepLinkStatus.kind === "test-not-found" ||
        deepLinkStatus.kind === "step-not-found" ||
        deepLinkStatus.kind === "unavailable") && (
        <div className={styles.section}>
          <Alert>{describeDeepLinkStatus(deepLinkStatus)}</Alert>
        </div>
      )}

      <div className={styles.section}>
        <TestResultsSection
          ref={testResultsRef}
          runId={runId}
          tests={tests}
          {...(artifactsErrorMessage !== undefined
            ? { artifactsErrorMessage }
            : {})}
        />
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
      {/* Distinguishes "no artifacts ever ingested" from "purged by retention" with an explicit message. */}
      {artifacts.isSuccess &&
        artifacts.data.length === 0 &&
        run.data?.artifactsPurged === true && (
          <div className={styles.section}>
            <Alert tone="info">
              Artifacts expired due to retention and are no longer available for
              download.
            </Alert>
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

/** `PROTOCOL_ERROR` covers three reducer statuses; branches so a recoverable gap reads differently than a contract violation. */
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
  const permissionMessage = describePermissionError(error);
  if (permissionMessage !== undefined) {
    return permissionMessage;
  }
  if (
    error instanceof RunnerApiError &&
    error.kind === "http" &&
    error.status === 404
  ) {
    return "This run is no longer available - the runner service may have restarted (run history is in-memory only, see docs/SSE_CONTRACT_V1.md).";
  }
  return error instanceof RunnerApiError ? error.message : "Unknown error";
}
