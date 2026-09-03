import { QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { StrictMode, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/query-client";
import { queryKeys } from "../../api/query-keys";
import { CURRENT_SCHEMA_VERSION } from "../../domain/runner-event";
import { FakeEventStreamClient } from "./fake-event-stream-client";
import {
  applyRunnerEventMessage,
  createInitialRunEventStreamState,
} from "./run-event-reducer";
import { useRunEventStream } from "./use-run-event-stream";

const RUN_ID = "run-1";

function event(overrides: Record<string, unknown>): string {
  return JSON.stringify({
    schemaVersion: CURRENT_SCHEMA_VERSION,
    runId: RUN_ID,
    timestamp: "2026-08-31T20:28:52Z",
    ...overrides,
  });
}

function setup(
  client: FakeEventStreamClient,
  { strict = false }: { strict?: boolean } = {},
) {
  const queryClient = createQueryClient();
  const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");
  const wrapper = ({ children }: { children: ReactNode }) => {
    const provider = (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
    return strict ? <StrictMode>{provider}</StrictMode> : provider;
  };
  const rendered = renderHook(() => useRunEventStream(RUN_ID, client), {
    wrapper,
  });
  return { ...rendered, invalidateSpy };
}

function runInvalidationCount(
  invalidateSpy: ReturnType<typeof vi.spyOn>,
  queryKey: unknown,
): number {
  return invalidateSpy.mock.calls.filter(
    ([arg]: [{ queryKey?: unknown } | undefined]) =>
      JSON.stringify(arg?.queryKey) === JSON.stringify(queryKey),
  ).length;
}

describe("useRunEventStream", () => {
  it("starts CONNECTING, then LIVE once the connection opens", () => {
    const client = new FakeEventStreamClient();
    const { result } = setup(client);

    expect(result.current.connectionState).toBe("CONNECTING");

    act(() => client.open());

    expect(result.current.connectionState).toBe("LIVE");
    expect(client.lastRunId).toBe(RUN_ID);
  });

  it("stays CONNECTING (not RECONNECTING) on an error before the connection has ever opened", () => {
    const client = new FakeEventStreamClient();
    const { result } = setup(client);

    act(() => client.error());

    expect(result.current.connectionState).toBe("CONNECTING");
  });

  it("moves to RECONNECTING on an error after having been open, then back to LIVE on recovery", () => {
    const client = new FakeEventStreamClient();
    const { result } = setup(client);

    act(() => client.open());
    act(() => client.error());
    expect(result.current.connectionState).toBe("RECONNECTING");

    act(() => client.open());
    expect(result.current.connectionState).toBe("LIVE");
  });

  it("applies replayed and live events through the same reducer path", () => {
    const client = new FakeEventStreamClient();
    const { result } = setup(client);

    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(event({ sequence: 2, type: "RUN_STARTED" }));
    });

    expect(result.current.streamState.status).toEqual({ kind: "active" });
    expect(result.current.streamState.lastSequence).toBe(2);
  });

  it("does not duplicate events across a reconnect (the reducer's own dedup applies)", () => {
    const client = new FakeEventStreamClient();
    const { result } = setup(client);

    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(event({ sequence: 2, type: "RUN_STARTED" }));
    });
    act(() => client.error());
    act(() => {
      client.open();
      // A reconnect naturally replays from the server's Last-Event-ID bookkeeping - here that's
      // sequence 1 and 2 arriving again before live delivery continues at 3.
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(event({ sequence: 2, type: "RUN_STARTED" }));
      client.emit(
        event({
          sequence: 3,
          type: "TEST_STARTED",
          testId: "test-a",
          testDisplayName: "a()",
        }),
      );
    });

    expect(result.current.streamState.lastSequence).toBe(3);
    expect(result.current.streamState.eventsBySequence.size).toBe(3);
  });

  it("on a gap: closes the old connection, resets the reducer, and opens one fresh-replay attempt", () => {
    const client = new FakeEventStreamClient();
    const { result } = setup(client);

    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(event({ sequence: 3, type: "RUN_STARTED" })); // expected 2, got 3
    });

    expect(result.current.connectionState).toBe("RECOVERING");
    // Fresh reducer state - sequence 0, not a patch on top of what the gap left behind.
    expect(result.current.streamState.status).toEqual({ kind: "active" });
    expect(result.current.streamState.lastSequence).toBe(0);
    expect(client.connectCallCount).toBe(2);
    expect(client.closeCallCount).toBe(1);
  });

  it("a successful fresh replay after a gap reconstructs an identical streamState to a clean run", () => {
    const client = new FakeEventStreamClient();
    const { result } = setup(client);

    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(event({ sequence: 3, type: "RUN_STARTED" })); // gap -> fresh-replay attempt
    });
    expect(result.current.connectionState).toBe("RECOVERING");

    const replayedEvents = [
      event({ sequence: 1, type: "RUN_QUEUED" }),
      event({ sequence: 2, type: "RUN_STARTED" }),
      event({
        sequence: 3,
        type: "TEST_STARTED",
        testId: "test-a",
        testDisplayName: "a()",
      }),
    ];

    // The backend's fresh-replay contract: a new EventSource with no Last-Event-ID gets the full
    // journal again, from sequence 1.
    act(() => {
      client.open();
      for (const raw of replayedEvents) {
        client.emit(raw);
      }
    });

    expect(result.current.connectionState).toBe("LIVE");
    // Full-state comparison, not just lastSequence/status/one test's partial status: applying the
    // identical correct sequence to a clean reducer must produce an indistinguishable streamState
    // (both `eventsBySequence` and `testsById`) - a bug that lost a displayName, timestamp, detail,
    // or an entry from `eventsBySequence` during the reset+fresh-replay path would still have
    // passed a narrower assertion.
    const expectedState = replayedEvents.reduce(
      (state, raw) => applyRunnerEventMessage(state, RUN_ID, raw),
      createInitialRunEventStreamState(),
    );
    expect(result.current.streamState).toEqual(expectedState);
  });

  it("a second gap after the fresh-replay attempt permanently freezes PROTOCOL_ERROR and invalidates REST caches", async () => {
    const client = new FakeEventStreamClient();
    const { result, invalidateSpy } = setup(client);

    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(event({ sequence: 3, type: "RUN_STARTED" })); // first gap -> fresh-replay attempt
    });
    expect(result.current.connectionState).toBe("RECOVERING");
    expect(client.connectCallCount).toBe(2);

    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(event({ sequence: 5, type: "RUN_STARTED" })); // second gap - budget exhausted
    });

    expect(result.current.connectionState).toBe("PROTOCOL_ERROR");
    expect(result.current.streamState.status.kind).toBe("gap");
    // No third attempt - one bounded retry, not a reconnect loop.
    expect(client.connectCallCount).toBe(2);
    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: queryKeys.runs });
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: queryKeys.run(RUN_ID),
      });
    });

    const stateAfterFreeze = result.current.streamState;
    act(() => client.emit(event({ sequence: 10, type: "RUN_STARTED" })));
    expect(result.current.streamState).toBe(stateAfterFreeze);
  });

  it("never retries a non-gap protocol-error (malformed JSON, wrong runId, a conflicting duplicate)", async () => {
    const client = new FakeEventStreamClient();
    const { result, invalidateSpy } = setup(client);

    act(() => {
      client.open();
      client.emit("{not json");
    });

    expect(result.current.connectionState).toBe("PROTOCOL_ERROR");
    expect(result.current.streamState.status.kind).toBe("protocol-error");
    expect(client.connectCallCount).toBe(1);
    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: queryKeys.run(RUN_ID),
      });
    });
  });

  it("never retries a compatibility-error (unsupported schema version)", () => {
    const client = new FakeEventStreamClient();
    const { result } = setup(client);

    act(() => {
      client.open();
      client.emit(
        event({ sequence: 1, type: "RUN_QUEUED", schemaVersion: "2.0" }),
      );
    });

    expect(result.current.connectionState).toBe("PROTOCOL_ERROR");
    expect(result.current.streamState.status.kind).toBe("compatibility-error");
    expect(client.connectCallCount).toBe(1);
  });

  it("does not reset the retry budget when only the injected client instance changes for the same run", () => {
    const clientA = new FakeEventStreamClient();
    const clientB = new FakeEventStreamClient();
    const queryClient = createQueryClient();
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
    const { result, rerender } = renderHook(
      ({ client }: { client: FakeEventStreamClient }) =>
        useRunEventStream(RUN_ID, client),
      { wrapper, initialProps: { client: clientA } },
    );

    act(() => {
      clientA.open();
      clientA.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      clientA.emit(event({ sequence: 3, type: "RUN_STARTED" })); // gap - consumes the one retry
    });
    expect(result.current.connectionState).toBe("RECOVERING");
    expect(clientA.connectCallCount).toBe(2);

    // Swapping the injected client for the same runId must not look like a fresh mount - the
    // retry budget stays consumed (RunDetailsPage's key={runId} is what resets it, not this).
    rerender({ client: clientB });

    act(() => {
      clientB.open();
      clientB.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      clientB.emit(event({ sequence: 5, type: "RUN_STARTED" })); // second gap - must not retry again
    });

    expect(result.current.connectionState).toBe("PROTOCOL_ERROR");
    // Only the one connect the client swap itself caused - no third (retry) attempt on clientB.
    expect(clientB.connectCallCount).toBe(1);
  });

  it("invalidates the run's REST details once the SSE lifecycle confirms RUN_STARTED", async () => {
    const client = new FakeEventStreamClient();
    const { invalidateSpy } = setup(client);

    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
    });
    expect(runInvalidationCount(invalidateSpy, queryKeys.run(RUN_ID))).toBe(0);

    act(() => client.emit(event({ sequence: 2, type: "RUN_STARTED" })));

    await waitFor(() => {
      expect(runInvalidationCount(invalidateSpy, queryKeys.run(RUN_ID))).toBe(
        1,
      );
    });
    // Not re-invalidated by unrelated later events.
    act(() => {
      client.emit(
        event({
          sequence: 3,
          type: "TEST_STARTED",
          testId: "test-a",
          testDisplayName: "a()",
        }),
      );
    });
    expect(runInvalidationCount(invalidateSpy, queryKeys.run(RUN_ID))).toBe(1);
  });

  it("on RUN_FINISHED: closes the connection, reports CLOSED, and invalidates runs queries", async () => {
    const client = new FakeEventStreamClient();
    const { result, invalidateSpy } = setup(client);

    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(
        event({ sequence: 2, type: "RUN_FINISHED", runOutcome: "SUCCEEDED" }),
      );
    });

    expect(result.current.connectionState).toBe("CLOSED");
    expect(client.isClosed).toBe(true);
    await waitFor(() => {
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: queryKeys.runs });
      expect(invalidateSpy).toHaveBeenCalledWith({
        queryKey: queryKeys.run(RUN_ID),
      });
    });

    // Prevents a reconnect loop: further events (even a live one arriving late) are ignored.
    const stateAfterFinish = result.current.streamState;
    act(() => client.emit(event({ sequence: 3, type: "RUN_STARTED" })));
    expect(result.current.streamState).toBe(stateAfterFinish);
  });

  it("under StrictMode, invalidates each query exactly once on RUN_FINISHED (updater must be pure)", async () => {
    const client = new FakeEventStreamClient();
    const { result, invalidateSpy } = setup(client, { strict: true });

    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(
        event({ sequence: 2, type: "RUN_FINISHED", runOutcome: "SUCCEEDED" }),
      );
    });

    expect(result.current.connectionState).toBe("CLOSED");
    await waitFor(() => {
      expect(
        runInvalidationCount(invalidateSpy, queryKeys.run(RUN_ID)),
      ).toBeGreaterThan(0);
    });

    expect(runInvalidationCount(invalidateSpy, queryKeys.runs)).toBe(1);
    expect(runInvalidationCount(invalidateSpy, queryKeys.run(RUN_ID))).toBe(1);
  });

  it("under StrictMode, invalidates REST caches exactly once once gap retries are exhausted", async () => {
    const client = new FakeEventStreamClient();
    const { result, invalidateSpy } = setup(client, { strict: true });

    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(event({ sequence: 3, type: "RUN_STARTED" })); // first gap -> fresh-replay attempt
    });
    expect(result.current.connectionState).toBe("RECOVERING");

    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(event({ sequence: 5, type: "RUN_STARTED" })); // second gap - budget exhausted
    });

    expect(result.current.connectionState).toBe("PROTOCOL_ERROR");
    await waitFor(() => {
      expect(
        runInvalidationCount(invalidateSpy, queryKeys.run(RUN_ID)),
      ).toBeGreaterThan(0);
    });

    expect(runInvalidationCount(invalidateSpy, queryKeys.runs)).toBe(1);
    expect(runInvalidationCount(invalidateSpy, queryKeys.run(RUN_ID))).toBe(1);
  });

  it("closes the connection on unmount", () => {
    const client = new FakeEventStreamClient();
    const { unmount } = setup(client);

    expect(client.isClosed).toBe(false);
    unmount();
    expect(client.isClosed).toBe(true);
    expect(client.closeCallCount).toBe(1);
  });
});
