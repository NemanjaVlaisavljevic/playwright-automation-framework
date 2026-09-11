import { EventType } from "../../domain/runner-event";

/** One `onEvent(raw)` callback for every SSE type; `applyRunnerEventMessage` parses `type` itself. */
export interface EventStreamHandlers {
  onEvent(raw: string): void;
  onOpen(): void;
  onError(): void;
}

export interface EventStreamConnection {
  close(): void;
}

export interface EventStreamClient {
  connect(runId: string, handlers: EventStreamHandlers): EventStreamConnection;
}

/**
 * Real `EventSource` against `GET /api/v1/runs/{runId}/events` (see docs/SSE_CONTRACT_V1.md).
 * Registers one named listener per `EventType` since the backend sends named SSE events, not
 * `message`. Reconnect/resume (`Last-Event-ID`) is handled natively by `EventSource`; this class
 * just forwards `open`/`error` for the caller's own connection-state tracking.
 */
export class EventSourceStreamClient implements EventStreamClient {
  connect(runId: string, handlers: EventStreamHandlers): EventStreamConnection {
    const eventSource = new EventSource(
      `/api/v1/runs/${encodeURIComponent(runId)}/events`,
    );

    for (const type of EventType.options) {
      eventSource.addEventListener(type, (event) => {
        handlers.onEvent((event as MessageEvent<string>).data);
      });
    }
    eventSource.addEventListener("open", () => handlers.onOpen());
    eventSource.addEventListener("error", () => handlers.onError());

    return {
      close: () => eventSource.close(),
    };
  }
}
