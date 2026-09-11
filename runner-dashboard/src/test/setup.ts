import "@testing-library/jest-dom/vitest";
import { afterAll, afterEach, beforeAll } from "vitest";
import { server } from "./msw/server";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

/**
 * jsdom has no `EventSource` global. This stub only avoids a `ReferenceError` when a test
 * incidentally mounts the real client; it never fires open/message/error. Real SSE behavior is
 * tested via `FakeEventStreamClient` (`use-run-event-stream.test.tsx`).
 */
class NoopEventSource {
  addEventListener(): void {}
  removeEventListener(): void {}
  close(): void {}
}
globalThis.EventSource ??= NoopEventSource as unknown as typeof EventSource;

/**
 * jsdom has no `Element.prototype.scrollIntoView`. Stubbed as a real no-op function (not left
 * undefined) so tests can `vi.spyOn` it.
 */
Element.prototype.scrollIntoView ??= () => {};
