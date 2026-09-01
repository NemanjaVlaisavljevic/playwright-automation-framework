import { EventType } from "../../domain/runner-event";

/**
 * Deliberately just `onEvent(raw: string)` - one callback for every event, not one per named SSE
 * type. `applyRunnerEventMessage` (the reducer) already parses `type` out of the JSON body itself
 * to decide what happened; the *transport* only needs to hand it the raw `data` string, replay or
 * live alike, without caring what kind of event it was.
 */
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
 * Production implementation: a real `EventSource` against `GET /api/v1/runs/{runId}/events` (see
 * docs/SSE_CONTRACT_V1.md in the repository root). Registers one named listener per event type in
 * `EventType.options` - the backend uses named SSE events (`event:RUN_STARTED`, etc.), and the
 * generic `onmessage` handler never fires for those, only for an unnamed `message` event.
 *
 * Resuming after a drop is entirely native `EventSource` behavior, not something this class
 * manages: the browser remembers the last event ID it received and automatically resends it as
 * `Last-Event-ID` on its own reconnect attempts (see SSE_CONTRACT_V1.md's own note on why a client
 * cannot set that header by hand). This class only needs to forward `open`/`error` so the caller's
 * own connection-state tracking (see `use-run-event-stream.ts`) can tell first-connect,
 * live, and reconnecting apart - `EventSource` itself makes no such distinction.
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
