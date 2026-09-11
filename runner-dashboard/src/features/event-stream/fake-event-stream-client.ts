import type {
  EventStreamClient,
  EventStreamConnection,
  EventStreamHandlers,
} from "./event-stream-client";

/**
 * Test double for {@link EventStreamClient}. Nothing fires on its own; tests drive the lifecycle
 * via {@link open}/{@link emit}/{@link error}.
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
