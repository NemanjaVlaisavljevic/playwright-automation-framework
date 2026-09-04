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

/**
 * jsdom does not implement `Element.prototype.scrollIntoView` - stubbed as a no-op so
 * `LiveFocusPanel`'s click-to-focus behavior doesn't throw. Assigned as a real function (not left
 * undefined) specifically so a test can `vi.spyOn(Element.prototype, "scrollIntoView")` to assert
 * it was called with the expected row, the same way any other browser-only side effect in this
 * suite is verified.
 */
Element.prototype.scrollIntoView ??= () => {};
