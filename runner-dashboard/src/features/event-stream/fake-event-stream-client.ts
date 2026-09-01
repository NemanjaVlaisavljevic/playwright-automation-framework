import type {
  EventStreamClient,
  EventStreamConnection,
  EventStreamHandlers,
} from "./event-stream-client";

/**
 * Test double for {@link EventStreamClient} - `connect()` stores the handlers and returns a
 * `close()` that just flips a flag; nothing fires on its own. Tests drive the connection lifecycle
 * explicitly via {@link open}/{@link emit}/{@link error}, in whatever order and interleaving a real
 * `EventSource` could produce (replay then live, a drop and recovery, an error before any open).
 */
export class FakeEventStreamClient implements EventStreamClient {
  private handlers: EventStreamHandlers | null = null;
  private closed = true;
  connectCallCount = 0;
  closeCallCount = 0;
  lastRunId: string | undefined;

  connect(runId: string, handlers: EventStreamHandlers): EventStreamConnection {
    this.connectCallCount += 1;
    this.lastRunId = runId;
    this.handlers = handlers;
    this.closed = false;
    return {
      close: () => {
        this.closed = true;
        this.closeCallCount += 1;
      },
    };
  }

  get isClosed(): boolean {
    return this.closed;
  }

  emit(raw: string): void {
    this.handlers?.onEvent(raw);
  }

  open(): void {
    this.handlers?.onOpen();
  }

  error(): void {
    this.handlers?.onError();
  }
}
