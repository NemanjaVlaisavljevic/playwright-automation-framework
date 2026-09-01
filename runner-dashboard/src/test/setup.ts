import "@testing-library/jest-dom/vitest";
import { afterAll, afterEach, beforeAll } from "vitest";
import { server } from "./msw/server";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

/**
 * jsdom (the test environment) does not implement `EventSource` as a global. Real SSE behavior is
 * exercised via `FakeEventStreamClient` injection (see `use-run-event-stream.test.tsx`); this stub
 * exists only so a component that mounts the *real* `EventSourceStreamClient` incidentally (e.g. a
 * router test that navigates through `RunDetailsPage` without caring about live updates) doesn't
 * crash with `ReferenceError: EventSource is not defined`. It never fires open/message/error - a
 * page relying on this stub just stays in "Connecting..." for the life of the test.
 */
class NoopEventSource {
  addEventListener(): void {}
  removeEventListener(): void {}
  close(): void {}
}
globalThis.EventSource ??= NoopEventSource as unknown as typeof EventSource;
