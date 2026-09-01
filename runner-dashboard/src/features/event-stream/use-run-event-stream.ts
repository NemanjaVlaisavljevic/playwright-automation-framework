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
 * `CONNECTING` (never yet open) and `RECONNECTING` (was open, dropped, `EventSource` is retrying on
 * its own) are deliberately distinct: `EventSource` itself fires the same `error` event for both,
 * so this hook is the one place that remembers whether the connection has ever reached `open` -
 * see `hasBeenOpen` below. `RECOVERING` is a fourth, hook-driven kind: a deliberate fresh-replay
 * attempt after a sequence gap, not anything `EventSource` itself reports - see `MAX_GAP_RETRIES`.
 */
export type ConnectionState =
  | "CONNECTING"
  | "LIVE"
  | "RECONNECTING"
  | "RECOVERING"
  | "PROTOCOL_ERROR"
  | "CLOSED";

/** The subset `onOpen`/`onError`/the retry logic can set directly - `CLOSED`/`PROTOCOL_ERROR` are
 * derived purely from `streamState.status` at render time, see `connectionState` below. */
type LiveConnectionState =
  "CONNECTING" | "LIVE" | "RECONNECTING" | "RECOVERING";

export interface UseRunEventStreamResult {
  connectionState: ConnectionState;
  streamState: RunEventStreamState;
}

const defaultClient = new EventSourceStreamClient();

/**
 * A sequence gap is the one frozen reducer status worth retrying automatically: it can be a
 * transient hiccup (a dropped frame, a reconnect race), and the backend's own contract is to
 * replay a run's full journal from the beginning to any client that connects with no
 * `Last-Event-ID` - which a brand-new `EventSource` object always is, never carrying over the
 * previous instance's state. `protocol-error` (malformed JSON, wrong `runId`, a conflicting
 * duplicate) and `compatibility-error` (unsupported `schemaVersion`) are deterministic contract
 * violations a reconnect cannot fix, so they go straight to a permanent `PROTOCOL_ERROR` instead.
 *
 * Bounded to exactly one attempt, and *per run* - this budget is never reset by the transport
 * effect itself (which also re-runs for a `client` change alone, not just a new run), only by an
 * actual fresh mount of this hook (`useReducer`'s own initial value), which is what `key={runId}`
 * on the caller guarantees for a genuinely new run (see `RunDetailsPage.tsx`). A run that gaps
 * twice is showing a real, not transient, problem, and retrying forever would just be a silent
 * reconnect loop.
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
 * Owns one run's live SSE connection and feeds every frame - replay or live, identically - into
 * `applyRunnerEventMessage` via `useReducer` (not a `useState` functional updater): the reducer
 * function itself must stay pure, since React (in development, under `StrictMode`) is explicitly
 * allowed to invoke it twice to check for exactly that - both `applyRunnerEventMessage` and the
 * `"reset"` branch below (`createInitialRunEventStreamState()`, itself pure) already are, so this
 * is free. Every side effect (closing the connection, invalidating REST caches, retrying) instead
 * lives in a plain `useEffect` below, driven off the resulting `streamState`, which React only
 * re-runs on a genuine state transition (the reducer returns the *same* object reference for a
 * benign duplicate/replay, so no spurious re-fires).
 *
 * On a `"gap"` with retry budget remaining: closes the current connection, dispatches `"reset"` to
 * start the reducer over from scratch, reports `"RECOVERING"`, and opens a brand-new connection
 * (see `MAX_GAP_RETRIES`) - `"RECOVERING"` is set explicitly (not derived from `streamState.status`
 * alone) specifically so it keeps showing across the render where the reset has already taken
 * `status.kind` back to `"active"` but the fresh connection hasn't reported `open` yet. On anything
 * else non-`"active"` (terminal, a gap with no budget left, or one of the reducer's other frozen
 * states): closes the connection for good and invalidates both `["runs"]` and `["runs", runId]` -
 * the authoritative `RunResponse` is always re-read over REST afterward, never synthesized from the
 * SSE event itself, and there is nothing useful left to receive by holding the connection open once
 * the reducer has permanently frozen.
 *
 * Separately, once the reducer's own `runStartedAt` is set (the SSE lifecycle confirming the run
 * left `QUEUED`/`STARTING`), the REST `RunResponse` is invalidated once more - otherwise a header
 * built from a `GET` that happened to catch `QUEUED` would show that status for the entire live run,
 * only ever refreshing once the run reaches a terminal state.
 *
 * Deliberately does not reset `streamState`/`connectionState` itself when `runId` changes -
 * `useReducer`'s/`useState`'s initial values already start fresh, so the caller forcing a remount
 * via `key={runId}` (see `RunDetailsPage.tsx`) is enough. Resetting state synchronously inside this
 * effect instead would work too, but is the exact "resetting state when a prop changes" antipattern
 * React's own docs steer away from in favor of a `key`.
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
  // `useReducer`, not `useState`: `dispatchLiveConnectionState` is called synchronously inside the
  // gap-retry effect below (for `"recovering"`), and a `useState` setter called that way trips
  // oxlint's `set-state-in-effect` the same way the old impure updater did - `dispatch` from
  // `useReducer` is exempt (see `gapRetriesUsed` below for the same reasoning in more detail).
  const [liveConnectionState, dispatchLiveConnectionState] = useReducer(
    reduceLiveConnectionState,
    "CONNECTING" as LiveConnectionState,
  );

  // A dedicated `useReducer`, not a ref: `gapRetriesUsed` is read during render (see
  // `isRecoveringFromGap` below), and React refs must never be read there - but a plain `useState`
  // setter called synchronously inside an effect trips oxlint's `set-state-in-effect` the same way
  // the old impure updater did. `dispatch` from `useReducer` is exempt from that same check (it
  // already is for the domain `streamState` above), so a trivial counter reducer gets both
  // properties: safe to read during render, and safe to call from inside an effect.
  const [gapRetriesUsed, dispatchGapRetryCount] = useReducer(
    (count: number, action: "increment" | "reset") =>
      action === "reset" ? 0 : count + 1,
    0,
  );

  const connectionRef = useRef<EventStreamConnection | null>(null);
  // Mirrors "this connection has been superseded" without waiting for a re-render - prevents
  // onOpen/onError from an old, already-closing connection from updating `liveConnectionState`
  // after the effect below has moved on (closed it for a retry, or frozen for good).
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

  // `liveConnectionState === "RECOVERING"` takes priority so it keeps showing through the render
  // where the reset above has already put `status.kind` back to `"active"` but the fresh
  // connection hasn't opened yet; the `"gap"` check below only covers the one render where a fresh
  // gap is detected but the retry effect hasn't run yet - together these avoid ever flashing
  // `"PROTOCOL_ERROR"` for a gap that's actually about to be retried.
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
