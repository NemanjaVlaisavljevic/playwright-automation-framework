import { useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useReducer, useRef } from "react";
import { queryKeys } from "../../api/query-keys";
import {
  EventSourceStreamClient,
  type EventStreamClient,
  type EventStreamConnection,
} from "./event-stream-client";
import {
  applyRunnerEventMessage,
  createInitialRunEventStreamState,
  type RunEventStreamState,
} from "./run-event-reducer";

/**
 * `CONNECTING` and `RECONNECTING` are distinct even though `EventSource` fires the same `error`
 * for both; this hook tracks whether the connection has ever reached `open` to tell them apart.
 * `RECOVERING` is hook-driven: a fresh-replay attempt after a sequence gap (see `MAX_GAP_RETRIES`).
 */
export type ConnectionState =
  | "CONNECTING"
  | "LIVE"
  | "RECONNECTING"
  | "RECOVERING"
  | "PROTOCOL_ERROR"
  | "CLOSED";

/** The subset settable directly; `CLOSED`/`PROTOCOL_ERROR` are derived from `streamState.status`. */
type LiveConnectionState =
  "CONNECTING" | "LIVE" | "RECONNECTING" | "RECOVERING";

export interface UseRunEventStreamResult {
  connectionState: ConnectionState;
  streamState: RunEventStreamState;
}

const defaultClient = new EventSourceStreamClient();

/**
 * A sequence gap is the only frozen status worth retrying: a fresh `EventSource` gets the backend's
 * full journal replay, so it can resolve a transient hiccup. `protocol-error`/`compatibility-error`
 * are deterministic and go straight to `PROTOCOL_ERROR`. Bounded to one attempt per run (reset only
 * by a fresh mount via `key={runId}`, see `RunDetailsPage.tsx`) - a second gap is a real problem.
 */
const MAX_GAP_RETRIES = 1;

type StreamAction = { kind: "message"; raw: string } | { kind: "reset" };

type LiveConnectionAction =
  | { kind: "open" }
  | { kind: "error"; hasBeenOpen: boolean }
  | { kind: "recovering" };

function reduceLiveConnectionState(
  _state: LiveConnectionState,
  action: LiveConnectionAction,
): LiveConnectionState {
  switch (action.kind) {
    case "open":
      return "LIVE";
    case "error":
      return action.hasBeenOpen ? "RECONNECTING" : "CONNECTING";
    case "recovering":
      return "RECOVERING";
  }
}

/**
 * Owns one run's live SSE connection, feeding every frame into `applyRunnerEventMessage` via
 * `useReducer` (must stay pure - React StrictMode double-invokes it). Side effects (closing the
 * connection, invalidating REST caches, retrying) live in `useEffect`s driven off `streamState`.
 *
 * On a `"gap"` with retry budget left: resets and reconnects, reporting `"RECOVERING"`. Otherwise
 * once non-`"active"`: closes for good and invalidates `["runs"]`/`["runs", runId]` so the
 * authoritative `RunResponse` is re-read over REST. Once `runStartedAt` is set, the REST snapshot
 * is invalidated again so a header that caught `QUEUED` doesn't stay stale for the whole run.
 *
 * Does not reset state itself when `runId` changes - relies on the caller remounting via
 * `key={runId}` (see `RunDetailsPage.tsx`), per React's own guidance over resetting in an effect.
 */
export function useRunEventStream(
  runId: string,
  client: EventStreamClient = defaultClient,
): UseRunEventStreamResult {
  const queryClient = useQueryClient();
  const [streamState, dispatch] = useReducer(
    (state: RunEventStreamState, action: StreamAction): RunEventStreamState =>
      action.kind === "reset"
        ? createInitialRunEventStreamState()
        : applyRunnerEventMessage(state, runId, action.raw),
    undefined,
    createInitialRunEventStreamState,
  );
  // useReducer (not useState) because dispatch is exempt from oxlint's set-state-in-effect check,
  // and this is called synchronously inside the gap-retry effect below.
  const [liveConnectionState, dispatchLiveConnectionState] = useReducer(
    reduceLiveConnectionState,
    "CONNECTING" as LiveConnectionState,
  );

  // useReducer, not a ref (read during render, see isRecoveringFromGap) or useState (its setter
  // would trip oxlint's set-state-in-effect when called synchronously below).
  const [gapRetriesUsed, dispatchGapRetryCount] = useReducer(
    (count: number, action: "increment" | "reset") =>
      action === "reset" ? 0 : count + 1,
    0,
  );

  const connectionRef = useRef<EventStreamConnection | null>(null);
  // Marks a connection as superseded so a stale onOpen/onError can't update state after the
  // effect below has moved on (retried or frozen for good).
  const frozenRef = useRef(false);

  const startConnection = useCallback(() => {
    frozenRef.current = false;
    let hasBeenOpen = false;

    connectionRef.current = client.connect(runId, {
      onOpen: () => {
        hasBeenOpen = true;
        if (!frozenRef.current) {
          dispatchLiveConnectionState({ kind: "open" });
        }
      },
      onError: () => {
        if (!frozenRef.current) {
          dispatchLiveConnectionState({ kind: "error", hasBeenOpen });
        }
      },
      onEvent: (raw) => {
        if (!frozenRef.current) {
          dispatch({ kind: "message", raw });
        }
      },
    });
  }, [client, runId, dispatch]);

  useEffect(() => {
    startConnection();

    return () => {
      frozenRef.current = true;
      connectionRef.current?.close();
      connectionRef.current = null;
    };
  }, [startConnection]);

  useEffect(() => {
    if (streamState.status.kind === "active") {
      return;
    }
    if (streamState.status.kind === "gap" && gapRetriesUsed < MAX_GAP_RETRIES) {
      dispatchGapRetryCount("increment");
      frozenRef.current = true;
      connectionRef.current?.close();
      dispatch({ kind: "reset" });
      dispatchLiveConnectionState({ kind: "recovering" });
      startConnection();
      return;
    }
    frozenRef.current = true;
    connectionRef.current?.close();
    void queryClient.invalidateQueries({ queryKey: queryKeys.runs });
    void queryClient.invalidateQueries({ queryKey: queryKeys.run(runId) });
  }, [streamState.status, gapRetriesUsed, runId, queryClient, startConnection]);

  useEffect(() => {
    if (streamState.runStartedAt !== undefined) {
      void queryClient.invalidateQueries({ queryKey: queryKeys.run(runId) });
    }
  }, [streamState.runStartedAt, runId, queryClient]);

  // Covers both the render where recovery is already in progress and the one where a fresh gap
  // is detected but the retry effect hasn't run yet, avoiding a flash of "PROTOCOL_ERROR".
  const isRecoveringFromGap =
    liveConnectionState === "RECOVERING" ||
    (streamState.status.kind === "gap" && gapRetriesUsed < MAX_GAP_RETRIES);

  const connectionState: ConnectionState =
    streamState.status.kind === "terminal"
      ? "CLOSED"
      : isRecoveringFromGap
        ? "RECOVERING"
        : streamState.status.kind !== "active"
          ? "PROTOCOL_ERROR"
          : liveConnectionState;

  return { connectionState, streamState };
}
