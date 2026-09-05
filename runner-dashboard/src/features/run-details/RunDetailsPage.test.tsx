import { QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import {
  createMemoryRouter,
  Link,
  MemoryRouter,
  Route,
  RouterProvider,
  Routes,
} from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/query-client";
import { CURRENT_SCHEMA_VERSION } from "../../domain/runner-event";
import { server } from "../../test/msw/server";
import { FakeEventStreamClient } from "../event-stream/fake-event-stream-client";
import { RunDetailsPage } from "./RunDetailsPage";
import { stepRowElementId } from "./run-details-view-model";

const RUN_ID = "run-1";

/**
 * The Tests table only - scopes a query away from the separate, always-present Artifacts table at
 * the bottom of the page, which now legitimately lists the very same artifact a step/test failure
 * also links to in its own context (see `FailureDetail`) - both are intentional, so tests that care
 * about a specific step/test's own link must not be tripped up by that second, unrelated copy.
 */
function testsTable() {
  return screen.getByRole("table", { name: `Tests for run ${RUN_ID}` });
}

/**
 * `LiveFocusPanel`'s own `<section aria-labelledby="...">` - a labelled `<section>` maps to ARIA
 * role `region`, and it's the only one on this page, so this stays stable across its own heading
 * text changing between "Active now (N)" and "Last known activity". Scoping to it is what keeps a
 * test about the panel from being tripped up by the Tests table legitimately showing the very same
 * test/step name.
 */
function liveFocusPanel() {
  return screen.getByRole("region");
}

function run(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    runId: RUN_ID,
    environment: "PUBLIC",
    suite: "SMOKE",
    status: "QUEUED",
    requestedAt: "2026-09-01T10:00:00Z",
    processLogUrl: `/api/v1/runs/${RUN_ID}/log`,
    selectedTests: [],
    ...overrides,
  };
}

function renderPage(
  client: FakeEventStreamClient,
  options: {
    runPollIntervalMs?: number;
    /** Defaults to `/runs/${RUN_ID}` with no query string - overridden by C4.5 deep-link tests to
     * seed `?testId=&stepId=`. */
    initialPath?: string;
  } = {},
) {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={[options.initialPath ?? `/runs/${RUN_ID}`]}>
        <Routes>
          <Route
            path="/runs/:runId"
            element={
              <RunDetailsPage
                eventStreamClient={client}
                {...(options.runPollIntervalMs !== undefined
                  ? { runPollIntervalMs: options.runPollIntervalMs }
                  : {})}
              />
            }
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function event(overrides: Record<string, unknown>): string {
  return JSON.stringify({
    schemaVersion: CURRENT_SCHEMA_VERSION,
    runId: RUN_ID,
    timestamp: "2026-09-01T10:00:05Z",
    ...overrides,
  });
}

/** Reads a `MetricCard`'s numeric value by its label - the value paragraph is always the label
 * paragraph's immediately preceding sibling (see `MetricCard.tsx`). */
function metricValue(label: string): string | null {
  // Scoped to a `<p>` (see `MetricCard.tsx`) - the C4.4 status filter's own `<option>` list now
  // contains this exact same word (e.g. "Running") as one of its choices, and a plain `getByText`
  // would otherwise match both.
  return (
    screen.getByText(label, { selector: "p" }).previousElementSibling
      ?.textContent ?? null
  );
}

describe("RunDetailsPage", () => {
  it("renders the run's header details from the REST response", async () => {
    server.use(http.get("/api/v1/runs/:runId", () => HttpResponse.json(run())));
    renderPage(new FakeEventStreamClient());

    expect(await screen.findByText("QUEUED")).toBeInTheDocument();
    expect(screen.getByText("SMOKE")).toBeInTheDocument();
    expect(screen.getByText("PUBLIC")).toBeInTheDocument();
    expect(screen.getByText("Sep 1, 2026, 10:00 AM")).toBeInTheDocument();
  });

  it("shows the connection banner and reflects live/reconnecting/closed transitions", async () => {
    server.use(http.get("/api/v1/runs/:runId", () => HttpResponse.json(run())));
    const client = new FakeEventStreamClient();
    renderPage(client);

    expect(await screen.findByRole("status")).toHaveTextContent(
      "Connecting to live results…",
    );

    act(() => client.open());
    expect(screen.getByRole("status")).toHaveTextContent("Live");

    act(() => client.error());
    expect(screen.getByRole("status")).toHaveTextContent(
      "Connection lost — reconnecting…",
    );
  });

  it("recovers automatically from a single gap: shows RECOVERING, then LIVE once the fresh replay opens", async () => {
    server.use(http.get("/api/v1/runs/:runId", () => HttpResponse.json(run())));
    const client = new FakeEventStreamClient();
    renderPage(client);

    await screen.findByText("QUEUED");
    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(event({ sequence: 3, type: "RUN_STARTED" })); // gap: expected 2, got 3
    });

    expect(screen.getByRole("status")).toHaveTextContent(
      "Live stream fell out of sync. Replaying from the beginning…",
    );
    expect(
      screen.queryByRole("link", { name: "Back to runs" }),
    ).not.toBeInTheDocument();

    act(() => client.open());
    expect(screen.getByRole("status")).toHaveTextContent("Live");
  });

  it("on a second gap after the fresh-replay attempt: shows a permanent error and a link back to runs", async () => {
    server.use(http.get("/api/v1/runs/:runId", () => HttpResponse.json(run())));
    const client = new FakeEventStreamClient();
    renderPage(client);

    await screen.findByText("QUEUED");
    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(event({ sequence: 3, type: "RUN_STARTED" })); // first gap -> fresh-replay attempt
    });
    expect(screen.getByRole("status")).toHaveTextContent(
      "Live stream fell out of sync. Replaying from the beginning…",
    );

    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(event({ sequence: 5, type: "RUN_STARTED" })); // second gap - budget exhausted
    });

    expect(screen.getByRole("status")).toHaveTextContent(
      "Live stream lost sync twice and could not recover automatically.",
    );
    expect(screen.getByRole("link", { name: "Back to runs" })).toHaveAttribute(
      "href",
      "/runs",
    );
  });

  it("updates progress counts and the tests table as TEST_* events stream in", async () => {
    server.use(http.get("/api/v1/runs/:runId", () => HttpResponse.json(run())));
    const client = new FakeEventStreamClient();
    renderPage(client);

    await screen.findByText("QUEUED");
    expect(screen.getByText("No tests started yet.")).toBeInTheDocument();

    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(event({ sequence: 2, type: "RUN_STARTED" }));
      client.emit(
        event({
          sequence: 3,
          type: "TEST_STARTED",
          testId: "test-a",
          testDisplayName: "loginTest()",
        }),
      );
    });

    expect(metricValue("Total")).toBe("1");
    expect(metricValue("Running")).toBe("1");
    expect(metricValue("Passed")).toBe("0");
    expect(
      screen.getByRole("cell", { name: "loginTest()" }),
    ).toBeInTheDocument();

    act(() => {
      client.emit(
        event({
          sequence: 4,
          type: "TEST_PASSED",
          testId: "test-a",
          testDisplayName: "loginTest()",
        }),
      );
    });

    expect(metricValue("Total")).toBe("1");
    expect(metricValue("Running")).toBe("0");
    expect(metricValue("Passed")).toBe("1");
  });

  it("renders nested step state and links a failed step's artifact (Faza B drill-down)", async () => {
    server.use(
      http.get("/api/v1/runs/:runId", () =>
        HttpResponse.json(run({ status: "FAILED" })),
      ),
      http.get("/api/v1/runs/:runId/artifacts", () =>
        HttpResponse.json([
          {
            artifactId: "trace-1",
            testId: "test-a",
            testDisplayName: "loginTest()",
            stepId: "step-2",
            type: "TRACE",
            mediaType: "application/zip",
            sizeBytes: 1024,
            createdAt: "2026-09-01T10:00:06Z",
            downloadUrl: `/api/v1/runs/${RUN_ID}/artifacts/trace-1`,
          },
        ]),
      ),
    );
    const client = new FakeEventStreamClient();
    renderPage(client);

    await screen.findByText("FAILED");

    act(() => {
      client.open();
      client.emit(
        event({
          sequence: 1,
          type: "TEST_STARTED",
          testId: "test-a",
          testDisplayName: "loginTest()",
        }),
      );
      client.emit(
        event({
          sequence: 2,
          type: "STEP_STARTED",
          testId: "test-a",
          testDisplayName: "loginTest()",
          stepId: "step-1",
          stepName: "open homepage",
        }),
      );
      client.emit(
        event({
          sequence: 3,
          type: "STEP_PASSED",
          testId: "test-a",
          testDisplayName: "loginTest()",
          stepId: "step-1",
          stepName: "open homepage",
        }),
      );
      client.emit(
        event({
          sequence: 4,
          type: "STEP_STARTED",
          testId: "test-a",
          testDisplayName: "loginTest()",
          stepId: "step-2",
          stepName: "assert confirmation banner",
        }),
      );
      client.emit(
        event({
          sequence: 5,
          type: "STEP_FAILED",
          testId: "test-a",
          testDisplayName: "loginTest()",
          stepId: "step-2",
          stepName: "assert confirmation banner",
          detail: "boom",
        }),
      );
      client.emit(
        event({
          sequence: 6,
          type: "TEST_FAILED",
          testId: "test-a",
          testDisplayName: "loginTest()",
          detail: "boom",
        }),
      );
    });

    // Test rows with steps are collapsed by default - expand before their steps are visible.
    await userEvent.click(screen.getByRole("button", { name: "loginTest()" }));

    expect(screen.getByText("open homepage")).toBeInTheDocument();
    expect(screen.getByText("assert confirmation banner")).toBeInTheDocument();
    expect(
      await within(testsTable()).findByRole("link", {
        name: "Download trace",
      }),
    ).toHaveAttribute("href", `/api/v1/runs/${RUN_ID}/artifacts/trace-1`);
  });

  /**
   * Regression test: artifacts must not wait for the whole run to finish. `AutomationExtension`
   * writes a failed test's manifest entry before the listener emits that test's own terminal
   * `TEST_*` event, so `TEST_FAILED`/`TEST_ABORTED` is itself a reliable "check again" signal - a
   * viewer should see a failed test's screenshot/trace immediately, not only after `RUN_FINISHED`.
   */
  it("shows a failed test's artifact before the run finishes, while a second test is still running", async () => {
    let artifactsCallCount = 0;
    server.use(
      http.get("/api/v1/runs/:runId", () =>
        HttpResponse.json(
          run({ status: "RUNNING", startedAt: "2026-09-01T10:00:01Z" }),
        ),
      ),
      http.get("/api/v1/runs/:runId/artifacts", () => {
        artifactsCallCount += 1;
        // The first fetch (on mount) sees nothing yet - no test had failed at that point. Only a
        // later refetch, triggered by the invalidation this test is proving, sees the artifact.
        if (artifactsCallCount === 1) {
          return HttpResponse.json([]);
        }
        return HttpResponse.json([
          {
            artifactId: "trace-1",
            testId: "test-a",
            testDisplayName: "aTest()",
            type: "TRACE",
            mediaType: "application/zip",
            sizeBytes: 1024,
            createdAt: "2026-09-01T10:00:06Z",
            downloadUrl: `/api/v1/runs/${RUN_ID}/artifacts/trace-1`,
          },
        ]);
      }),
    );
    const client = new FakeEventStreamClient();
    renderPage(client);

    await screen.findByText("RUNNING");

    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_STARTED" }));
      client.emit(
        event({
          sequence: 2,
          type: "TEST_STARTED",
          testId: "test-a",
          testDisplayName: "aTest()",
        }),
      );
      client.emit(
        event({
          sequence: 3,
          type: "TEST_FAILED",
          testId: "test-a",
          testDisplayName: "aTest()",
          detail: "boom",
        }),
      );
      client.emit(
        event({
          sequence: 4,
          type: "TEST_STARTED",
          testId: "test-b",
          testDisplayName: "bTest()",
        }),
      );
    });

    // test-b is still RUNNING and no RUN_FINISHED has been emitted - the stream must still be live,
    // not the "Run finished." banner - while the failed test's artifact is already visible.
    expect(screen.getByText("Live")).toBeInTheDocument();
    expect(
      await within(testsTable()).findByRole("link", {
        name: "Download trace",
      }),
    ).toHaveAttribute("href", `/api/v1/runs/${RUN_ID}/artifacts/trace-1`);
  });

  /**
   * Regression test: `stepId` is scoped to one test, not globally unique (see `RunnerEvent`'s own
   * contract) - two different tests are free to reuse the same `stepId`. Grouping artifacts by
   * `stepId` alone would show a single test's artifact under both tests' matching step.
   */
  it("does not leak an artifact into another test's step that happens to share the same stepId", async () => {
    server.use(
      http.get("/api/v1/runs/:runId", () =>
        HttpResponse.json(run({ status: "FAILED" })),
      ),
      http.get("/api/v1/runs/:runId/artifacts", () =>
        HttpResponse.json([
          {
            artifactId: "trace-1",
            testId: "test-a",
            testDisplayName: "aTest()",
            stepId: "shared-step",
            type: "TRACE",
            mediaType: "application/zip",
            sizeBytes: 1024,
            createdAt: "2026-09-01T10:00:06Z",
            downloadUrl: `/api/v1/runs/${RUN_ID}/artifacts/trace-1`,
          },
        ]),
      ),
    );
    const client = new FakeEventStreamClient();
    renderPage(client);

    await screen.findByText("FAILED");

    act(() => {
      client.open();
      client.emit(
        event({
          sequence: 1,
          type: "TEST_STARTED",
          testId: "test-a",
          testDisplayName: "aTest()",
        }),
      );
      client.emit(
        event({
          sequence: 2,
          type: "STEP_STARTED",
          testId: "test-a",
          testDisplayName: "aTest()",
          stepId: "shared-step",
          stepName: "step in test a",
        }),
      );
      client.emit(
        event({
          sequence: 3,
          type: "STEP_PASSED",
          testId: "test-a",
          testDisplayName: "aTest()",
          stepId: "shared-step",
          stepName: "step in test a",
        }),
      );
      client.emit(
        event({
          sequence: 4,
          type: "TEST_PASSED",
          testId: "test-a",
          testDisplayName: "aTest()",
        }),
      );
      client.emit(
        event({
          sequence: 5,
          type: "TEST_STARTED",
          testId: "test-b",
          testDisplayName: "bTest()",
        }),
      );
      client.emit(
        event({
          sequence: 6,
          type: "STEP_STARTED",
          testId: "test-b",
          testDisplayName: "bTest()",
          stepId: "shared-step",
          stepName: "step in test b",
        }),
      );
      client.emit(
        event({
          sequence: 7,
          type: "STEP_FAILED",
          testId: "test-b",
          testDisplayName: "bTest()",
          stepId: "shared-step",
          stepName: "step in test b",
          detail: "boom in b",
        }),
      );
      client.emit(
        event({
          sequence: 8,
          type: "TEST_FAILED",
          testId: "test-b",
          testDisplayName: "bTest()",
          detail: "boom in b",
        }),
      );
    });

    // Test rows with steps are collapsed by default - expand both before their steps are visible.
    await userEvent.click(screen.getByRole("button", { name: "aTest()" }));
    await userEvent.click(screen.getByRole("button", { name: "bTest()" }));

    expect(screen.getByText("step in test a")).toBeInTheDocument();
    expect(screen.getByText("step in test b")).toBeInTheDocument();
    // The artifact belongs only to test-a's step - not duplicated onto test-b's step of the same
    // stepId. Scoped to the Tests table so this stays about that leak, not the separate, unrelated
    // second copy the bottom Artifacts table always renders for every artifact.
    expect(
      await within(testsTable()).findAllByRole("link", {
        name: "Download trace",
      }),
    ).toHaveLength(1);
  });

  it("shows the failed step in a collapsed row's failure preview without auto-expanding, and stops showing it once the row is expanded", async () => {
    server.use(
      http.get("/api/v1/runs/:runId", () =>
        HttpResponse.json(run({ status: "FAILED" })),
      ),
      http.get("/api/v1/runs/:runId/artifacts", () =>
        HttpResponse.json([
          {
            artifactId: "trace-1",
            testId: "test-a",
            testDisplayName: "loginTest()",
            stepId: "step-2",
            type: "TRACE",
            mediaType: "application/zip",
            sizeBytes: 1024,
            createdAt: "2026-09-01T10:00:06Z",
            downloadUrl: `/api/v1/runs/${RUN_ID}/artifacts/trace-1`,
          },
        ]),
      ),
    );
    const client = new FakeEventStreamClient();
    renderPage(client);

    await screen.findByText("FAILED");
    act(() => {
      client.open();
      client.emit(
        event({
          sequence: 1,
          type: "TEST_STARTED",
          testId: "test-a",
          testDisplayName: "loginTest()",
        }),
      );
      client.emit(
        event({
          sequence: 2,
          type: "STEP_STARTED",
          testId: "test-a",
          testDisplayName: "loginTest()",
          stepId: "step-1",
          stepName: "open homepage",
        }),
      );
      client.emit(
        event({
          sequence: 3,
          type: "STEP_PASSED",
          testId: "test-a",
          testDisplayName: "loginTest()",
          stepId: "step-1",
          stepName: "open homepage",
        }),
      );
      client.emit(
        event({
          sequence: 4,
          type: "STEP_STARTED",
          testId: "test-a",
          testDisplayName: "loginTest()",
          stepId: "step-2",
          stepName: "assert confirmation banner",
        }),
      );
      client.emit(
        event({
          sequence: 5,
          type: "STEP_FAILED",
          testId: "test-a",
          testDisplayName: "loginTest()",
          stepId: "step-2",
          stepName: "assert confirmation banner",
          detail: "boom",
        }),
      );
      client.emit(
        event({
          sequence: 6,
          type: "TEST_FAILED",
          testId: "test-a",
          testDisplayName: "loginTest()",
          detail: "boom",
        }),
      );
    });

    // Still collapsed: the failed step's own name, summary and artifact link are visible without
    // clicking anything, but the passed step's own row is not - only the failure preview rendered,
    // not the full step list.
    const preview = within(testsTable())
      .getByText("assert confirmation banner")
      .closest("td")!;
    expect(within(preview).getByText("boom")).toBeInTheDocument();
    expect(
      within(testsTable()).queryByText("open homepage"),
    ).not.toBeInTheDocument();
    expect(
      await within(preview).findByRole("link", { name: "Download trace" }),
    ).toHaveAttribute("href", `/api/v1/runs/${RUN_ID}/artifacts/trace-1`);

    // Expanding the row shows the same failed step as part of the full step list - the preview must
    // not also still be there, or the failed step's name and its failure actions would now appear
    // twice.
    await userEvent.click(screen.getByRole("button", { name: "loginTest()" }));
    expect(screen.getByText("open homepage")).toBeInTheDocument();
    expect(screen.getAllByText("assert confirmation banner")).toHaveLength(1);
    expect(
      screen.getAllByRole("button", { name: "Copy failure" }),
    ).toHaveLength(1);
  });

  it("copies a failed step's full failure text verbatim, and only discloses 'View full detail' when there is more than the one-line summary", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
    const fullDetail =
      "AssertionError: expected true but was false\n\tat Foo.bar(Foo.java:12)";
    server.use(
      http.get("/api/v1/runs/:runId", () =>
        HttpResponse.json(run({ status: "FAILED" })),
      ),
      http.get("/api/v1/runs/:runId/artifacts", () => HttpResponse.json([])),
    );
    const client = new FakeEventStreamClient();
    renderPage(client);

    await screen.findByText("FAILED");
    act(() => {
      client.open();
      client.emit(
        event({
          sequence: 1,
          type: "TEST_STARTED",
          testId: "test-a",
          testDisplayName: "loginTest()",
        }),
      );
      client.emit(
        event({
          sequence: 2,
          type: "STEP_STARTED",
          testId: "test-a",
          testDisplayName: "loginTest()",
          stepId: "step-1",
          stepName: "assert confirmation banner",
        }),
      );
      client.emit(
        event({
          sequence: 3,
          type: "STEP_FAILED",
          testId: "test-a",
          testDisplayName: "loginTest()",
          stepId: "step-1",
          stepName: "assert confirmation banner",
          detail: fullDetail,
        }),
      );
      client.emit(
        event({
          sequence: 4,
          type: "TEST_FAILED",
          testId: "test-a",
          testDisplayName: "loginTest()",
          detail: fullDetail,
        }),
      );
    });

    // The one-line summary is only the first line...
    expect(
      within(testsTable()).getByText(
        "AssertionError: expected true but was false",
      ),
    ).toBeInTheDocument();
    // ...but the full, multi-line text - stack frame included - is still available verbatim.
    const disclosure = screen.getByText("View full detail").closest("details");
    expect(disclosure).toHaveTextContent("Foo.bar(Foo.java:12)");

    await userEvent.click(screen.getByRole("button", { name: "Copy failure" }));
    expect(writeText).toHaveBeenCalledWith(fullDetail);
  });

  it("shows a scoped artifacts-load error next to the specific failing test it belongs to, not a passing test's row", async () => {
    server.use(
      http.get("/api/v1/runs/:runId", () =>
        HttpResponse.json(run({ status: "FAILED" })),
      ),
      http.get(
        "/api/v1/runs/:runId/artifacts",
        () => new HttpResponse(null, { status: 503 }),
      ),
    );
    const client = new FakeEventStreamClient();
    renderPage(client);

    await screen.findByText("FAILED");
    act(() => {
      client.open();
      client.emit(
        event({
          sequence: 1,
          type: "TEST_STARTED",
          testId: "test-a",
          testDisplayName: "aTest()",
        }),
      );
      client.emit(
        event({
          sequence: 2,
          type: "TEST_FAILED",
          testId: "test-a",
          testDisplayName: "aTest()",
          detail: "boom",
        }),
      );
      client.emit(
        event({
          sequence: 3,
          type: "TEST_STARTED",
          testId: "test-b",
          testDisplayName: "bTest()",
        }),
      );
      client.emit(
        event({
          sequence: 4,
          type: "TEST_PASSED",
          testId: "test-b",
          testDisplayName: "bTest()",
        }),
      );
    });

    const banner = await screen.findByText(/Could not load artifacts:/);
    const scopedMessage = banner.textContent!.replace(
      /^Could not load artifacts: /,
      "",
    );

    // Appears exactly once more, scoped inside the Tests table next to test-a's own failure - not
    // duplicated, and not attached to test-b's (passing) row.
    expect(within(testsTable()).getByText(scopedMessage)).toBeInTheDocument();
    expect(screen.getAllByText(scopedMessage)).toHaveLength(1);
  });

  /**
   * Regression test (review finding, P1): a test can have steps that all reported a real terminal
   * result (none `FAILED`) and still itself end `FAILED` - e.g. a failure during cleanup, after
   * every step already passed. `primaryFailure` is then test-scoped, not step-scoped, and has no
   * counterpart anywhere in the step list - it must stay visible after expanding, not disappear the
   * way a step-scoped preview correctly does.
   */
  it("keeps a test-level fallback failure visible after expanding, when no step explains it", async () => {
    server.use(
      http.get("/api/v1/runs/:runId", () =>
        HttpResponse.json(run({ status: "FAILED" })),
      ),
      http.get("/api/v1/runs/:runId/artifacts", () =>
        HttpResponse.json([
          {
            artifactId: "screenshot-1",
            testId: "test-a",
            testDisplayName: "aTest()",
            type: "SCREENSHOT",
            mediaType: "image/png",
            sizeBytes: 2048,
            createdAt: "2026-09-01T10:00:06Z",
            downloadUrl: `/api/v1/runs/${RUN_ID}/artifacts/screenshot-1`,
          },
          {
            artifactId: "trace-1",
            testId: "test-a",
            testDisplayName: "aTest()",
            type: "TRACE",
            mediaType: "application/zip",
            sizeBytes: 1024,
            createdAt: "2026-09-01T10:00:06Z",
            downloadUrl: `/api/v1/runs/${RUN_ID}/artifacts/trace-1`,
          },
        ]),
      ),
    );
    const client = new FakeEventStreamClient();
    renderPage(client);

    await screen.findByText("FAILED");
    act(() => {
      client.open();
      client.emit(
        event({
          sequence: 1,
          type: "TEST_STARTED",
          testId: "test-a",
          testDisplayName: "aTest()",
        }),
      );
      client.emit(
        event({
          sequence: 2,
          type: "STEP_STARTED",
          testId: "test-a",
          testDisplayName: "aTest()",
          stepId: "step-1",
          stepName: "provision a room",
        }),
      );
      client.emit(
        event({
          sequence: 3,
          type: "STEP_PASSED",
          testId: "test-a",
          testDisplayName: "aTest()",
          stepId: "step-1",
          stepName: "provision a room",
        }),
      );
      // No STEP_FAILED anywhere - this test fails outright (e.g. during cleanup) after its only
      // step already passed.
      client.emit(
        event({
          sequence: 4,
          type: "TEST_FAILED",
          testId: "test-a",
          testDisplayName: "aTest()",
          detail: "boom during cleanup",
        }),
      );
    });

    // Collapsed: the test-level failure preview is visible, with its own artifacts.
    expect(
      within(testsTable()).getByText("boom during cleanup"),
    ).toBeInTheDocument();
    expect(
      await within(testsTable()).findByRole("link", {
        name: "Open screenshot",
      }),
    ).toBeInTheDocument();
    expect(
      within(testsTable()).getByRole("link", { name: "Download trace" }),
    ).toBeInTheDocument();

    // Expanding shows the passed step - but must NOT hide the test-level failure, since nothing in
    // the step list explains or repeats it. Scoped to the Tests table throughout - the bottom
    // Artifacts table separately (and legitimately) lists the very same artifacts on its own.
    await userEvent.click(screen.getByRole("button", { name: "aTest()" }));
    expect(screen.getByText("provision a room")).toBeInTheDocument();
    expect(
      within(testsTable()).getByText("boom during cleanup"),
    ).toBeInTheDocument();
    expect(
      within(testsTable()).getByRole("button", { name: "Copy failure" }),
    ).toBeInTheDocument();
    expect(
      within(testsTable()).getByRole("link", { name: "Open screenshot" }),
    ).toBeInTheDocument();
    expect(
      within(testsTable()).getByRole("link", { name: "Download trace" }),
    ).toBeInTheDocument();
  });

  /**
   * Regression test (review finding, P2): once `FailureDetail` exists, the test row's own legacy
   * "Detail" disclosure must not also show the same failure text - that would put the same content
   * on the page through two different disclosure paths (or, for a single-line detail, twice as
   * plain visible text).
   */
  it("shows a failed test's failure content exactly once, with no parallel legacy Detail disclosure", async () => {
    server.use(
      http.get("/api/v1/runs/:runId", () =>
        HttpResponse.json(run({ status: "FAILED" })),
      ),
      http.get("/api/v1/runs/:runId/artifacts", () => HttpResponse.json([])),
    );
    const client = new FakeEventStreamClient();
    renderPage(client);

    await screen.findByText("FAILED");
    act(() => {
      client.open();
      client.emit(
        event({
          sequence: 1,
          type: "TEST_STARTED",
          testId: "test-a",
          testDisplayName: "aTest()",
        }),
      );
      client.emit(
        event({
          sequence: 2,
          type: "TEST_FAILED",
          testId: "test-a",
          testDisplayName: "aTest()",
          detail: "boom",
        }),
      );
    });

    expect(within(testsTable()).getAllByText("boom")).toHaveLength(1);
    expect(
      screen.getAllByRole("button", { name: "Copy failure" }),
    ).toHaveLength(1);
    // The legacy per-row Detail cell defers to FailureDetail instead of duplicating it.
    expect(screen.getByText("See failure below")).toBeInTheDocument();
    expect(
      screen.queryByText("Detail", { selector: "summary" }),
    ).not.toBeInTheDocument();
  });

  it("auto-expands a still-RUNNING test's steps, then collapses them once it finishes with no manual choice made", async () => {
    server.use(http.get("/api/v1/runs/:runId", () => HttpResponse.json(run())));
    const client = new FakeEventStreamClient();
    renderPage(client);

    await screen.findByText("QUEUED");
    act(() => {
      client.open();
      client.emit(
        event({
          sequence: 1,
          type: "TEST_STARTED",
          testId: "test-a",
          testDisplayName: "aTest()",
        }),
      );
      client.emit(
        event({
          sequence: 2,
          type: "STEP_STARTED",
          testId: "test-a",
          testDisplayName: "aTest()",
          stepId: "step-1",
          stepName: "open homepage",
        }),
      );
    });

    // No click at all - the row is open purely because the test is still RUNNING. Scoped to the
    // Tests table: `LiveFocusPanel` now legitimately shows this same still-RUNNING step's name too.
    expect(within(testsTable()).getByText("open homepage")).toBeInTheDocument();

    act(() => {
      client.emit(
        event({
          sequence: 3,
          type: "STEP_PASSED",
          testId: "test-a",
          testDisplayName: "aTest()",
          stepId: "step-1",
          stepName: "open homepage",
        }),
      );
      client.emit(
        event({
          sequence: 4,
          type: "TEST_PASSED",
          testId: "test-a",
          testDisplayName: "aTest()",
        }),
      );
    });

    // The user never made an explicit choice, so the row reverts to the normal collapsed default
    // once the test is no longer RUNNING.
    expect(
      within(testsTable()).queryByText("open homepage"),
    ).not.toBeInTheDocument();
  });

  it("keeps a manually-collapsed test's steps hidden after it finishes running", async () => {
    server.use(http.get("/api/v1/runs/:runId", () => HttpResponse.json(run())));
    const client = new FakeEventStreamClient();
    renderPage(client);

    await screen.findByText("QUEUED");
    act(() => {
      client.open();
      client.emit(
        event({
          sequence: 1,
          type: "TEST_STARTED",
          testId: "test-a",
          testDisplayName: "aTest()",
        }),
      );
      client.emit(
        event({
          sequence: 2,
          type: "STEP_STARTED",
          testId: "test-a",
          testDisplayName: "aTest()",
          stepId: "step-1",
          stepName: "open homepage",
        }),
      );
    });
    // Scoped to the Tests table throughout this test: `LiveFocusPanel` shows this same step's name
    // for as long as the test is RUNNING, independently of the row's own manual collapse state.
    expect(within(testsTable()).getByText("open homepage")).toBeInTheDocument();

    // An explicit choice while still RUNNING - overriding the auto-open default.
    await userEvent.click(screen.getByRole("button", { name: "aTest()" }));
    expect(
      within(testsTable()).queryByText("open homepage"),
    ).not.toBeInTheDocument();

    act(() => {
      client.emit(
        event({
          sequence: 3,
          type: "STEP_PASSED",
          testId: "test-a",
          testDisplayName: "aTest()",
          stepId: "step-1",
          stepName: "open homepage",
        }),
      );
      client.emit(
        event({
          sequence: 4,
          type: "TEST_PASSED",
          testId: "test-a",
          testDisplayName: "aTest()",
        }),
      );
    });

    // The explicit choice survives the RUNNING -> terminal transition.
    expect(
      within(testsTable()).queryByText("open homepage"),
    ).not.toBeInTheDocument();
  });

  it("shows each step's own duration once it finishes", async () => {
    server.use(http.get("/api/v1/runs/:runId", () => HttpResponse.json(run())));
    const client = new FakeEventStreamClient();
    renderPage(client);

    await screen.findByText("QUEUED");
    act(() => {
      client.open();
      client.emit(
        event({
          sequence: 1,
          type: "TEST_STARTED",
          testId: "test-a",
          testDisplayName: "aTest()",
        }),
      );
      client.emit(
        event({
          sequence: 2,
          type: "STEP_STARTED",
          testId: "test-a",
          testDisplayName: "aTest()",
          stepId: "step-1",
          stepName: "provision a room",
          timestamp: "2026-09-01T10:00:00Z",
        }),
      );
      client.emit(
        event({
          sequence: 3,
          type: "STEP_PASSED",
          testId: "test-a",
          testDisplayName: "aTest()",
          stepId: "step-1",
          stepName: "provision a room",
          timestamp: "2026-09-01T10:00:05Z",
        }),
      );
      client.emit(
        event({
          sequence: 4,
          type: "TEST_PASSED",
          testId: "test-a",
          testDisplayName: "aTest()",
        }),
      );
    });

    // Test rows with steps are collapsed by default once finished - expand before the duration is
    // visible.
    await userEvent.click(screen.getByRole("button", { name: "aTest()" }));

    expect(screen.getByText("provision a room")).toBeInTheDocument();
    expect(screen.getByText("5s")).toBeInTheDocument();
  });

  /**
   * Regression test for the C4.1 finding: previously `RUN_FINISHED` never touched `testsById`, so a
   * test (and its step) that was still RUNNING the instant the run ended - here, because the user
   * cancelled it mid-step - stayed rendered as RUNNING forever, even though the run itself already
   * shows a terminal status. The view model must relabel both as INTERRUPTED, using the run's own
   * `finishedAt` for duration display so it stops advancing rather than ticking against `Date.now()`
   * forever.
   */
  it("shows a test and its still-RUNNING step as INTERRUPTED once the run is cancelled mid-step, with a fixed duration", async () => {
    let cancelled = false;
    server.use(
      http.get("/api/v1/runs/:runId", () =>
        HttpResponse.json(
          run(
            cancelled
              ? {
                  status: "CANCELLED",
                  startedAt: "2026-09-01T10:00:00Z",
                  finishedAt: "2026-09-01T10:00:20Z",
                }
              : { status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" },
          ),
        ),
      ),
      http.post("/api/v1/runs/:runId/cancel", () => {
        cancelled = true;
        return HttpResponse.json(
          run({
            status: "CANCELLED",
            startedAt: "2026-09-01T10:00:00Z",
            finishedAt: "2026-09-01T10:00:20Z",
          }),
        );
      }),
    );
    const client = new FakeEventStreamClient();
    renderPage(client);

    await screen.findByText("RUNNING");
    act(() => {
      client.open();
      client.emit(
        event({
          sequence: 1,
          type: "TEST_STARTED",
          testId: "test-a",
          testDisplayName: "aTest()",
        }),
      );
      client.emit(
        event({
          sequence: 2,
          type: "STEP_STARTED",
          testId: "test-a",
          testDisplayName: "aTest()",
          stepId: "step-1",
          stepName: "provision a room",
          timestamp: "2026-09-01T10:00:05Z",
        }),
      );
      // Deliberately no STEP_PASSED/STEP_FAILED or TEST_PASSED/TEST_FAILED - the process is killed
      // before either can be reported, exactly as a real cancellation mid-step looks on the wire.
    });

    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    await screen.findByText("CANCELLED");

    // The test row was auto-expanded while it was still RUNNING, but INTERRUPTED is a terminal
    // display status like any other - the row correctly reverts to its normal collapsed default
    // (see the "auto-expand must use display status" requirement), so an explicit click is needed
    // to see its step.
    await userEvent.click(screen.getByRole("button", { name: "aTest()" }));

    expect(screen.getAllByText("INTERRUPTED")).toHaveLength(2); // the test row and its one step
    expect(
      screen.getByText(
        "Run ended before this test reported a terminal result.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Run ended before this step reported a terminal result.",
      ),
    ).toBeInTheDocument();
    // Duration fixed at the run's own finishedAt (20s after the test started) - not still advancing
    // against real wall-clock time by the time this assertion runs.
    expect(screen.getByText("20s")).toBeInTheDocument();
  });

  it("flags a data-integrity warning when a SUCCEEDED run still reports an interrupted test", async () => {
    server.use(
      http.get("/api/v1/runs/:runId", () =>
        HttpResponse.json(
          run({
            status: "SUCCEEDED",
            startedAt: "2026-09-01T10:00:00Z",
            finishedAt: "2026-09-01T10:00:20Z",
          }),
        ),
      ),
    );
    const client = new FakeEventStreamClient();
    renderPage(client);

    await screen.findByText("SUCCEEDED");
    act(() => {
      client.open();
      client.emit(
        event({
          sequence: 1,
          type: "TEST_STARTED",
          testId: "test-a",
          testDisplayName: "aTest()",
        }),
      );
      // No TEST_PASSED/TEST_FAILED - a SUCCEEDED run can never legitimately have this happen, which
      // is exactly what makes it an integrity warning rather than an ordinary interruption.
    });

    expect(
      await screen.findByText(/event stream may be incomplete/),
    ).toBeInTheDocument();
  });

  /**
   * Regression test for a real review finding: the terminal reconciliation must not depend on a
   * REST refetch. `useRunEventStream` triggers a `run` query refetch the instant the stream itself
   * goes non-active (including reaching `"terminal"`) - if that refetch fails (or returns a stale
   * snapshot), the REST-only `run.data.status`/`finishedAt` could stay stuck on RUNNING forever,
   * reintroducing exactly the bug C4.1 fixed. The SSE stream's own `RUN_FINISHED` timestamp must be
   * the primary terminal-time signal, not just a REST fallback.
   */
  it("reconciles a test/step as INTERRUPTED from the stream's own RUN_FINISHED timestamp even when the final REST refetch fails", async () => {
    let getRunCallCount = 0;
    server.use(
      http.get("/api/v1/runs/:runId", () => {
        getRunCallCount += 1;
        if (getRunCallCount === 1) {
          return HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          );
        }
        // The refetch `useRunEventStream` fires once the stream reaches "terminal" - simulated here
        // as failing outright, so `run.data` never advances past the initial RUNNING snapshot.
        return HttpResponse.error();
      }),
    );
    const client = new FakeEventStreamClient();
    renderPage(client);

    await screen.findByText("RUNNING");
    act(() => {
      client.open();
      client.emit(
        event({
          sequence: 1,
          type: "TEST_STARTED",
          testId: "test-a",
          testDisplayName: "aTest()",
          timestamp: "2026-09-01T10:00:00Z",
        }),
      );
      client.emit(
        event({
          sequence: 2,
          type: "STEP_STARTED",
          testId: "test-a",
          testDisplayName: "aTest()",
          stepId: "step-1",
          stepName: "provision a room",
          timestamp: "2026-09-01T10:00:05Z",
        }),
      );
      client.emit(
        event({
          sequence: 3,
          type: "RUN_FINISHED",
          runOutcome: "CANCELLED",
          timestamp: "2026-09-01T10:00:20Z",
        }),
      );
    });

    // Confirms the refetch this test relies on actually happened (and failed) - not just that the
    // assertions below happen to pass regardless.
    await waitFor(() => expect(getRunCallCount).toBeGreaterThan(1));

    await userEvent.click(screen.getByRole("button", { name: "aTest()" }));
    expect(screen.getAllByText("INTERRUPTED")).toHaveLength(2);
    // Test duration: 10:00:00 -> 10:00:20 (RUN_FINISHED's own timestamp), not real elapsed time.
    expect(screen.getByText("20s")).toBeInTheDocument();
    // Step duration: 10:00:05 -> 10:00:20.
    expect(screen.getByText("15s")).toBeInTheDocument();
  });

  it("copies the run ID to the clipboard and briefly confirms it in the button's own label", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
    server.use(http.get("/api/v1/runs/:runId", () => HttpResponse.json(run())));
    renderPage(new FakeEventStreamClient());

    await screen.findByText("QUEUED");
    await userEvent.click(screen.getByRole("button", { name: "Copy" }));

    expect(writeText).toHaveBeenCalledWith(RUN_ID);
    expect(
      await screen.findByRole("button", { name: "Copied!" }),
    ).toBeInTheDocument();
  });

  it("resets the confirmation timer on a second Copy click instead of racing the first one's revert", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText },
      configurable: true,
    });
    server.use(http.get("/api/v1/runs/:runId", () => HttpResponse.json(run())));
    renderPage(new FakeEventStreamClient());
    await screen.findByText("QUEUED");

    // fireEvent (not userEvent) + manual microtask flushing: userEvent's own internal delay
    // handling doesn't mix reliably with fake timers, but the actual behavior under test - two
    // clicks racing a setTimeout - needs the fake clock to be advanceable independently of any
    // real wall-clock delay.
    vi.useFakeTimers();
    try {
      await act(async () => {
        fireEvent.click(screen.getByRole("button", { name: "Copy" }));
        // Two microtask ticks: one for `writeText`'s own resolution, one for `handleCopy`'s
        // continuation after that `await` to actually run and call `setCopied`.
        await Promise.resolve();
        await Promise.resolve();
      });
      expect(
        screen.getByRole("button", { name: "Copied!" }),
      ).toBeInTheDocument();

      act(() => {
        vi.advanceTimersByTime(1000);
      });
      expect(
        screen.getByRole("button", { name: "Copied!" }),
      ).toBeInTheDocument();

      // A second click 1000ms into the first click's 1500ms revert window must push that
      // deadline out again, not leave the first timer running alongside a second one.
      await act(async () => {
        fireEvent.click(screen.getByRole("button", { name: "Copied!" }));
        await Promise.resolve();
        await Promise.resolve();
      });

      act(() => {
        vi.advanceTimersByTime(1000);
      });
      // If the first timer had not been cleared, it would already have reverted this by now -
      // 1000ms (before the second click) + 1000ms (after it) = 2000ms since the first click,
      // past its own 1500ms deadline.
      expect(
        screen.getByRole("button", { name: "Copied!" }),
      ).toBeInTheDocument();

      act(() => {
        vi.advanceTimersByTime(500);
      });
      expect(screen.getByRole("button", { name: "Copy" })).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it("hides Cancel and shows no Download link for a non-terminal run with no startedAt yet", async () => {
    server.use(
      http.get("/api/v1/runs/:runId", () =>
        HttpResponse.json(run({ status: "QUEUED" })),
      ),
    );
    renderPage(new FakeEventStreamClient());

    await screen.findByText("QUEUED");
    expect(screen.getByRole("button", { name: "Cancel" })).toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: "Download log" }),
    ).not.toBeInTheDocument();
  });

  it("shows the Download log link once the run has started, and hides Cancel once terminal", async () => {
    server.use(
      http.get("/api/v1/runs/:runId", () =>
        HttpResponse.json(
          run({
            status: "SUCCEEDED",
            startedAt: "2026-09-01T10:00:05Z",
            finishedAt: "2026-09-01T10:01:00Z",
          }),
        ),
      ),
    );
    renderPage(new FakeEventStreamClient());

    await screen.findByText("SUCCEEDED");
    expect(
      screen.queryByRole("button", { name: "Cancel" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Download log" })).toHaveAttribute(
      "href",
      `/api/v1/runs/${RUN_ID}/log`,
    );
    expect(screen.getByText("Sep 1, 2026, 10:01 AM")).toBeInTheDocument();
  });

  it("cancels the run via the Cancel button", async () => {
    let cancelled = false;
    server.use(
      http.get("/api/v1/runs/:runId", () =>
        HttpResponse.json(run({ status: cancelled ? "CANCELLED" : "QUEUED" })),
      ),
      http.post("/api/v1/runs/:runId/cancel", () => {
        cancelled = true;
        return HttpResponse.json(run({ status: "CANCELLED" }));
      }),
    );
    renderPage(new FakeEventStreamClient());

    await screen.findByText("QUEUED");
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    await screen.findByText("CANCELLED");
    expect(
      screen.queryByRole("button", { name: "Cancel" }),
    ).not.toBeInTheDocument();
  });

  it("shows a visible error and re-enables Cancel when the cancel request 503s", async () => {
    server.use(
      http.get("/api/v1/runs/:runId", () => HttpResponse.json(run())),
      http.post(
        "/api/v1/runs/:runId/cancel",
        () => new HttpResponse(null, { status: 503 }),
      ),
    );
    renderPage(new FakeEventStreamClient());

    await screen.findByText("QUEUED");
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(
      await screen.findByText(/Could not cancel run:/),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cancel" })).toBeEnabled();
  });

  it("shows a visible error when the cancel request fails at the network level", async () => {
    server.use(
      http.get("/api/v1/runs/:runId", () => HttpResponse.json(run())),
      http.post("/api/v1/runs/:runId/cancel", () => HttpResponse.error()),
    );
    renderPage(new FakeEventStreamClient());

    await screen.findByText("QUEUED");
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(
      await screen.findByText(/Could not reach the runner service/),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Cancel" })).toBeEnabled();
  });

  it("refreshes the stale REST status once the SSE lifecycle confirms RUN_STARTED", async () => {
    let getCount = 0;
    server.use(
      http.get("/api/v1/runs/:runId", () => {
        getCount += 1;
        return HttpResponse.json(
          getCount === 1
            ? run({ status: "QUEUED" })
            : run({ status: "RUNNING", startedAt: "2026-09-01T10:00:05Z" }),
        );
      }),
    );
    const client = new FakeEventStreamClient();
    renderPage(client);

    // Initial GET catches the run still QUEUED - Download log correctly absent (no startedAt yet).
    await screen.findByText("QUEUED");
    expect(
      screen.queryByRole("link", { name: "Download log" }),
    ).not.toBeInTheDocument();

    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(event({ sequence: 2, type: "RUN_STARTED" }));
    });

    // Without the fix, this REST snapshot would stay QUEUED for the rest of the live run - only
    // RUN_FINISHED ever triggered a refetch, so the header/Cancel gating would be stuck stale.
    await screen.findByText("RUNNING");
    expect(
      screen.getByRole("link", { name: "Download log" }),
    ).toBeInTheDocument();
  });

  it("shows a specific message when the run's REST lookup 404s", async () => {
    server.use(
      http.get(
        "/api/v1/runs/:runId",
        () => new HttpResponse(null, { status: 404 }),
      ),
    );
    renderPage(new FakeEventStreamClient());

    expect(
      await screen.findByText(/This run is no longer available/),
    ).toBeInTheDocument();
  });

  it("shows no Artifacts section when the run has produced none", async () => {
    server.use(
      http.get("/api/v1/runs/:runId", () => HttpResponse.json(run())),
      http.get("/api/v1/runs/:runId/artifacts", () => HttpResponse.json([])),
    );
    renderPage(new FakeEventStreamClient());

    await screen.findByText("QUEUED");
    expect(
      screen.queryByRole("heading", { name: "Artifacts" }),
    ).not.toBeInTheDocument();
  });

  it("shows a screenshot thumbnail and a trace download link once artifacts are captured", async () => {
    server.use(
      http.get("/api/v1/runs/:runId", () =>
        HttpResponse.json(run({ status: "FAILED" })),
      ),
      http.get("/api/v1/runs/:runId/artifacts", () =>
        HttpResponse.json([
          {
            artifactId: "shot-1",
            testId: "test-a",
            testDisplayName: "loginTest()",
            type: "SCREENSHOT",
            mediaType: "image/png",
            sizeBytes: 2048,
            createdAt: "2026-09-01T10:00:05Z",
            downloadUrl: `/api/v1/runs/${RUN_ID}/artifacts/shot-1`,
          },
          {
            artifactId: "trace-1",
            testId: "test-a",
            testDisplayName: "loginTest()",
            type: "TRACE",
            mediaType: "application/zip",
            sizeBytes: 1_258_291,
            createdAt: "2026-09-01T10:00:06Z",
            downloadUrl: `/api/v1/runs/${RUN_ID}/artifacts/trace-1`,
          },
        ]),
      ),
    );
    renderPage(new FakeEventStreamClient());

    expect(
      await screen.findByRole("heading", { name: "Artifacts" }),
    ).toBeInTheDocument();
    expect(screen.getByText("2.0 KB")).toBeInTheDocument();
    expect(screen.getByText("1.2 MB")).toBeInTheDocument();
    expect(
      screen.getByRole("img", { name: "Screenshot for loginTest()" }),
    ).toHaveAttribute("src", `/api/v1/runs/${RUN_ID}/artifacts/shot-1`);
    expect(
      screen.getByRole("link", { name: "Download trace" }),
    ).toHaveAttribute("href", `/api/v1/runs/${RUN_ID}/artifacts/trace-1`);
  });

  it("shows a visible error when the artifacts request fails", async () => {
    server.use(
      http.get("/api/v1/runs/:runId", () => HttpResponse.json(run())),
      http.get(
        "/api/v1/runs/:runId/artifacts",
        () => new HttpResponse(null, { status: 503 }),
      ),
    );
    renderPage(new FakeEventStreamClient());

    expect(
      await screen.findByText(/Could not load artifacts:/),
    ).toBeInTheDocument();
  });

  it("never calls the artifacts endpoint when the run itself 404s (regression: duplicated 'not available' text)", async () => {
    let artifactsRequested = false;
    server.use(
      http.get(
        "/api/v1/runs/:runId",
        () => new HttpResponse(null, { status: 404 }),
      ),
      http.get("/api/v1/runs/:runId/artifacts", () => {
        artifactsRequested = true;
        return HttpResponse.json([]);
      }),
    );
    renderPage(new FakeEventStreamClient());

    expect(
      await screen.findByText(/This run is no longer available/),
    ).toBeInTheDocument();
    expect(
      screen.queryByText(/Could not load artifacts:/),
    ).not.toBeInTheDocument();
    expect(artifactsRequested).toBe(false);
  });

  it("recovers a stuck run status and its artifacts via REST polling once the stream permanently freezes (regression)", async () => {
    // Without the P1 fix: `useRunEventStream` invalidates `run` exactly once when the stream
    // freezes into PROTOCOL_ERROR, but if the run is still RUNNING at that one refetch, nothing
    // else ever refetches it again - the header stays stuck on RUNNING and the Artifacts section
    // (gated on the run reaching a terminal status) never appears, even once the backend actually
    // finishes the run and captures a screenshot.
    let runFinished = false;
    let runRequestCount = 0;
    server.use(
      http.get("/api/v1/runs/:runId", () => {
        runRequestCount += 1;
        return HttpResponse.json(
          runFinished
            ? run({
                status: "FAILED",
                startedAt: "2026-09-01T10:00:05Z",
                finishedAt: "2026-09-01T10:00:30Z",
              })
            : run({ status: "RUNNING", startedAt: "2026-09-01T10:00:05Z" }),
        );
      }),
      http.get("/api/v1/runs/:runId/artifacts", () =>
        HttpResponse.json(
          runFinished
            ? [
                {
                  artifactId: "shot-1",
                  testId: "test-a",
                  testDisplayName: "loginTest()",
                  type: "SCREENSHOT",
                  mediaType: "image/png",
                  sizeBytes: 2048,
                  createdAt: "2026-09-01T10:00:29Z",
                  downloadUrl: `/api/v1/runs/${RUN_ID}/artifacts/shot-1`,
                },
              ]
            : [],
        ),
      ),
    );
    const client = new FakeEventStreamClient();
    renderPage(client, { runPollIntervalMs: 20 });

    await screen.findByText("RUNNING");
    expect(
      screen.queryByRole("heading", { name: "Artifacts" }),
    ).not.toBeInTheDocument();

    // Two sequence gaps exhausts the one fresh-replay retry budget - the same pattern the
    // permanent-protocol-error test above uses to force a real, non-recoverable PROTOCOL_ERROR.
    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(event({ sequence: 3, type: "RUN_STARTED" }));
    });
    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(event({ sequence: 5, type: "RUN_STARTED" }));
    });
    expect(screen.getByRole("status")).toHaveTextContent(
      "Live stream lost sync twice and could not recover automatically.",
    );

    // The backend finishes the run and captures a screenshot only after the stream has already
    // frozen for good - nothing in the SSE stream itself will ever report this.
    runFinished = true;

    expect(await screen.findByText("FAILED")).toBeInTheDocument();
    expect(
      await screen.findByRole("heading", { name: "Artifacts" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("img", { name: "Screenshot for loginTest()" }),
    ).toHaveAttribute("src", `/api/v1/runs/${RUN_ID}/artifacts/shot-1`);

    // The other half of the production requirement: reaching a terminal status must actually stop
    // the fallback poll, not just successfully recover once. `runPollIntervalMs: 20` above means
    // several intervals easily fit in this wait - without the terminal-status check in
    // `refetchInterval`, this would keep incrementing.
    const countAtTerminal = runRequestCount;
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 200));
    });
    expect(runRequestCount).toBe(countAtTerminal);
  });

  it("stops REST fallback polling once the run permanently 404s, rather than polling a run that will never come back (regression)", async () => {
    let runGone = false;
    let runRequestCount = 0;
    server.use(
      http.get("/api/v1/runs/:runId", () => {
        runRequestCount += 1;
        if (runGone) {
          return new HttpResponse(null, { status: 404 });
        }
        return HttpResponse.json(
          run({ status: "RUNNING", startedAt: "2026-09-01T10:00:05Z" }),
        );
      }),
      http.get("/api/v1/runs/:runId/artifacts", () => HttpResponse.json([])),
    );
    const client = new FakeEventStreamClient();
    renderPage(client, { runPollIntervalMs: 20 });

    await screen.findByText("RUNNING");
    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(event({ sequence: 3, type: "RUN_STARTED" }));
    });
    act(() => {
      client.open();
      client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
      client.emit(event({ sequence: 5, type: "RUN_STARTED" }));
    });
    expect(screen.getByRole("status")).toHaveTextContent(
      "Live stream lost sync twice and could not recover automatically.",
    );

    // The run vanishes for good - e.g. the runner service restarted and lost its in-memory history
    // (see docs/SSE_CONTRACT_V1.md) - rather than ever coming back with a terminal status. Without
    // the 404 check, `refetchInterval` would keep polling a run that will never exist again.
    runGone = true;
    await screen.findByText(/This run is no longer available/);

    const countAtNotFound = runRequestCount;
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 200));
    });
    expect(runRequestCount).toBe(countAtNotFound);
  });

  it("resets progress/tests when navigating directly from one run's page to another's", async () => {
    server.use(
      http.get("/api/v1/runs/:runId", ({ params }) =>
        HttpResponse.json(run({ runId: params.runId })),
      ),
    );
    const client = new FakeEventStreamClient();
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter initialEntries={["/runs/run-1"]}>
          <Link to="/runs/run-2">Go to run-2</Link>
          <Routes>
            <Route
              path="/runs/:runId"
              element={<RunDetailsPage eventStreamClient={client} />}
            />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await screen.findByRole("heading", { name: /run-1/ });
    act(() => {
      client.open();
      client.emit(event({ runId: "run-1", sequence: 1, type: "RUN_QUEUED" }));
      client.emit(event({ runId: "run-1", sequence: 2, type: "RUN_STARTED" }));
      client.emit(
        event({
          runId: "run-1",
          sequence: 3,
          type: "TEST_STARTED",
          testId: "test-a",
          testDisplayName: "loginTest()",
        }),
      );
    });
    expect(
      screen.getByRole("cell", { name: "loginTest()" }),
    ).toBeInTheDocument();

    await userEvent.click(screen.getByRole("link", { name: "Go to run-2" }));

    await screen.findByRole("heading", { name: /run-2/ });
    expect(screen.getByText("No tests started yet.")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(
      "Connecting to live results…",
    );
  });

  describe("LiveFocusPanel (C4.2)", () => {
    it("shows a single active test with no steps yet as waiting for the next reported step", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
      });

      expect(
        within(liveFocusPanel()).getByRole("heading", {
          name: "Active now (1)",
        }),
      ).toBeInTheDocument();
      expect(within(liveFocusPanel()).getByText("aTest()")).toBeInTheDocument();
      expect(
        within(liveFocusPanel()).getByText("Waiting for next reported step…"),
      ).toBeInTheDocument();
    });

    it("shows an active test's currently RUNNING step name", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({
            sequence: 2,
            type: "STEP_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
            stepId: "step-1",
            stepName: "open homepage",
          }),
        );
      });

      // Scoped to the panel - the Tests table's own auto-expanded step list shows this exact same
      // step name too, since the test is still RUNNING.
      expect(
        within(liveFocusPanel()).getByText("open homepage"),
      ).toBeInTheDocument();
    });

    it("shows two parallel active tests, in a stable order that a new step never reshuffles", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({
            sequence: 2,
            type: "TEST_STARTED",
            testId: "test-b",
            testDisplayName: "bTest()",
          }),
        );
      });

      expect(
        within(liveFocusPanel()).getByRole("heading", {
          name: "Active now (2)",
        }),
      ).toBeInTheDocument();
      const namesBefore = within(liveFocusPanel())
        .getAllByRole("button")
        .map((button) => button.textContent);
      expect(namesBefore[0]).toContain("aTest()");
      expect(namesBefore[1]).toContain("bTest()");

      // A new step for the *second* test (the one ordered later by firstSequence) must not promote
      // it ahead of the first - order is by firstSequence, not by "most recently updated".
      act(() => {
        client.emit(
          event({
            sequence: 3,
            type: "STEP_STARTED",
            testId: "test-b",
            testDisplayName: "bTest()",
            stepId: "step-1",
            stepName: "provision a room",
          }),
        );
      });
      const namesAfter = within(liveFocusPanel())
        .getAllByRole("button")
        .map((button) => button.textContent);
      expect(namesAfter[0]).toContain("aTest()");
      expect(namesAfter[1]).toContain("bTest()");
    });

    it("drops a test from the panel once it finishes, while a second test remains active", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({
            sequence: 2,
            type: "TEST_STARTED",
            testId: "test-b",
            testDisplayName: "bTest()",
          }),
        );
      });
      expect(
        within(liveFocusPanel()).getByRole("heading", {
          name: "Active now (2)",
        }),
      ).toBeInTheDocument();

      act(() => {
        client.emit(
          event({
            sequence: 3,
            type: "TEST_PASSED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
      });

      expect(
        within(liveFocusPanel()).getByRole("heading", {
          name: "Active now (1)",
        }),
      ).toBeInTheDocument();
      expect(
        within(liveFocusPanel()).queryByText("aTest()"),
      ).not.toBeInTheDocument();
      expect(within(liveFocusPanel()).getByText("bTest()")).toBeInTheDocument();
    });

    it("switches the shown step name as a test moves from one step to the next", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({
            sequence: 2,
            type: "STEP_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
            stepId: "step-1",
            stepName: "open homepage",
          }),
        );
      });
      expect(
        within(liveFocusPanel()).getByText("open homepage"),
      ).toBeInTheDocument();

      act(() => {
        client.emit(
          event({
            sequence: 3,
            type: "STEP_PASSED",
            testId: "test-a",
            testDisplayName: "aTest()",
            stepId: "step-1",
            stepName: "open homepage",
          }),
        );
        client.emit(
          event({
            sequence: 4,
            type: "STEP_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
            stepId: "step-2",
            stepName: "assert homepage loaded",
          }),
        );
      });

      expect(
        within(liveFocusPanel()).queryByText("open homepage"),
      ).not.toBeInTheDocument();
      expect(
        within(liveFocusPanel()).getByText("assert homepage loaded"),
      ).toBeInTheDocument();
    });

    it("removes the panel entirely once the run reaches its terminal RUN_FINISHED event", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
      });
      expect(screen.getByRole("region")).toBeInTheDocument();

      act(() => {
        client.emit(
          event({
            sequence: 2,
            type: "TEST_PASSED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({
            sequence: 3,
            type: "RUN_FINISHED",
            runOutcome: "SUCCEEDED",
          }),
        );
      });

      expect(screen.queryByRole("region")).not.toBeInTheDocument();
    });

    /**
     * A test still (wire-level) RUNNING can be reconciled to the display-only `INTERRUPTED` status
     * even while the *live* connection itself is merely `RECONNECTING`, not yet `CLOSED` - the view
     * model's `runIsTerminal` also considers the REST `RunResponse` (see `RunDetailsPage.tsx`), so a
     * backend that has already finished the run can race a dropped `EventSource` that hasn't
     * reconnected to receive the final `RUN_FINISHED` frame yet. `INTERRUPTED` must never count as
     * active in that window either.
     */
    /**
     * Regression test (review finding, P2): a dropped `EventSource` can sit in `RECONNECTING`
     * well after the REST fallback has already confirmed the run is over - the panel must hide
     * entirely once the run's own *effective* status (REST here, since the SSE stream itself never
     * reached its own `RUN_FINISHED`) is terminal, not keep showing "Last known activity" (or
     * worse, imply there might still be something active) just because `connectionState` alone
     * hasn't caught up. This test previously asserted the opposite (a real review finding: it had
     * cemented exactly the wrong behavior) - see the PR history for the original assertions.
     */
    it("hides entirely once the run's own REST status is terminal, even while merely RECONNECTING", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({
              status: "CANCELLED",
              startedAt: "2026-09-01T10:00:00Z",
              finishedAt: "2026-09-01T10:00:20Z",
            }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("CANCELLED");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.error();
      });

      expect(within(testsTable()).getByText("INTERRUPTED")).toBeInTheDocument();
      expect(screen.queryByRole("region")).not.toBeInTheDocument();
    });

    it("keeps showing the last known active test while RECONNECTING, under a 'Last known activity' heading", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
      });
      expect(
        within(liveFocusPanel()).getByRole("heading", {
          name: "Active now (1)",
        }),
      ).toBeInTheDocument();

      act(() => client.error());

      expect(
        within(liveFocusPanel()).getByRole("heading", {
          name: "Last known activity",
        }),
      ).toBeInTheDocument();
      expect(within(liveFocusPanel()).getByText("aTest()")).toBeInTheDocument();
    });

    it("clicking an active test scrolls to and focuses its row, without disturbing a manual collapse", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({
            sequence: 2,
            type: "STEP_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
            stepId: "step-1",
            stepName: "open homepage",
          }),
        );
      });

      // Manually collapse the row - it starts auto-expanded because the test is RUNNING.
      await userEvent.click(screen.getByRole("button", { name: "aTest()" }));
      expect(
        within(testsTable()).queryByText("open homepage"),
      ).not.toBeInTheDocument();

      const scrollIntoView = vi.spyOn(Element.prototype, "scrollIntoView");
      await userEvent.click(
        within(liveFocusPanel()).getByRole("button", { name: /aTest\(\)/ }),
      );

      const row = document.getElementById("test-test-a");
      expect(scrollIntoView).toHaveBeenCalled();
      expect(document.activeElement).toBe(row);
      // The click only scrolled/focused - it must not have re-expanded the row the user
      // deliberately collapsed.
      expect(
        within(testsTable()).queryByText("open homepage"),
      ).not.toBeInTheDocument();

      scrollIntoView.mockRestore();
    });

    it("never scrolls or moves focus on its own just because a new SSE event arrived", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
      });

      const scrollIntoView = vi.spyOn(Element.prototype, "scrollIntoView");
      act(() => {
        client.emit(
          event({
            sequence: 2,
            type: "STEP_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
            stepId: "step-1",
            stepName: "open homepage",
          }),
        );
        client.emit(
          event({
            sequence: 3,
            type: "STEP_PASSED",
            testId: "test-a",
            testDisplayName: "aTest()",
            stepId: "step-1",
            stepName: "open homepage",
          }),
        );
      });

      expect(scrollIntoView).not.toHaveBeenCalled();
      scrollIntoView.mockRestore();
    });
  });

  describe("TestResultsSection filters (C4.4)", () => {
    it("a step-name-only search reveals and force-expands the parent test with every step, and clearing it returns the previous manual (collapsed) choice", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "loginTest()",
          }),
        );
        client.emit(
          event({
            sequence: 2,
            type: "STEP_STARTED",
            testId: "test-a",
            testDisplayName: "loginTest()",
            stepId: "step-1",
            stepName: "open homepage",
          }),
        );
        client.emit(
          event({
            sequence: 3,
            type: "STEP_PASSED",
            testId: "test-a",
            testDisplayName: "loginTest()",
            stepId: "step-1",
            stepName: "open homepage",
          }),
        );
        client.emit(
          event({
            sequence: 4,
            type: "STEP_STARTED",
            testId: "test-a",
            testDisplayName: "loginTest()",
            stepId: "step-2",
            stepName: "submit credentials",
          }),
        );
      });

      // RUNNING auto-expands by default - collapse it manually first, so this test actually proves
      // search returns the *previous manual* choice, not just the RUNNING auto-default.
      await userEvent.click(
        screen.getByRole("button", { name: "loginTest()" }),
      );
      expect(
        within(testsTable()).queryByText("open homepage"),
      ).not.toBeInTheDocument();

      await userEvent.type(
        screen.getByLabelText("Search tests or steps"),
        "credentials",
      );

      // Force-expanded: every step shows for context, not just the one that matched.
      expect(
        within(testsTable()).getByText("open homepage"),
      ).toBeInTheDocument();
      expect(
        within(testsTable()).getByText("submit credentials"),
      ).toBeInTheDocument();

      await userEvent.clear(screen.getByLabelText("Search tests or steps"));

      expect(
        within(testsTable()).queryByText("open homepage"),
      ).not.toBeInTheDocument();
    });

    it("selecting a status filter shows only matching tests, and a live RUNNING -> PASSED transition immediately drops it from the RUNNING filter", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({
            sequence: 2,
            type: "TEST_STARTED",
            testId: "test-b",
            testDisplayName: "bTest()",
          }),
        );
        client.emit(
          event({
            sequence: 3,
            type: "TEST_PASSED",
            testId: "test-b",
            testDisplayName: "bTest()",
          }),
        );
      });

      await userEvent.selectOptions(screen.getByLabelText("Status"), "RUNNING");

      expect(within(testsTable()).getByText("aTest()")).toBeInTheDocument();
      expect(
        within(testsTable()).queryByText("bTest()"),
      ).not.toBeInTheDocument();

      act(() => {
        client.emit(
          event({
            sequence: 4,
            type: "TEST_PASSED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
      });

      // Both tests are now PASSED - nothing matches the still-active RUNNING filter, so the table
      // itself is gone in favor of the empty state (not just "aTest() no longer shown").
      expect(
        screen.getByText("No tests match the current filters."),
      ).toBeInTheDocument();
    });

    it("selecting the Has artifacts evidence filter shows only tests with captured evidence", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(run({ status: "FAILED" })),
        ),
        http.get("/api/v1/runs/:runId/artifacts", () =>
          HttpResponse.json([
            {
              artifactId: "trace-1",
              testId: "test-a",
              testDisplayName: "aTest()",
              type: "TRACE",
              mediaType: "application/zip",
              sizeBytes: 1024,
              createdAt: "2026-09-01T10:00:06Z",
              downloadUrl: `/api/v1/runs/${RUN_ID}/artifacts/trace-1`,
            },
          ]),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("FAILED");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({
            sequence: 2,
            type: "TEST_FAILED",
            testId: "test-a",
            testDisplayName: "aTest()",
            detail: "boom",
          }),
        );
        client.emit(
          event({
            sequence: 3,
            type: "TEST_STARTED",
            testId: "test-b",
            testDisplayName: "bTest()",
          }),
        );
        client.emit(
          event({
            sequence: 4,
            type: "TEST_FAILED",
            testId: "test-b",
            testDisplayName: "bTest()",
            detail: "also boom",
          }),
        );
      });

      await userEvent.selectOptions(
        screen.getByLabelText("Evidence"),
        "HAS_ARTIFACTS",
      );

      expect(
        await within(testsTable()).findByText("aTest()"),
      ).toBeInTheDocument();
      expect(
        within(testsTable()).queryByText("bTest()"),
      ).not.toBeInTheDocument();
    });

    it("an active filter with zero matches stays selected, shows the empty state, and Clear filters brings every test back", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () => HttpResponse.json(run())),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("QUEUED");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({
            sequence: 2,
            type: "TEST_PASSED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
      });

      const statusSelect = screen.getByLabelText("Status");
      await userEvent.selectOptions(statusSelect, "FAILED");

      expect(
        screen.getByText("No tests match the current filters."),
      ).toBeInTheDocument();
      // The selection itself is not silently reset just because it currently matches nothing.
      expect(statusSelect).toHaveValue("FAILED");

      await userEvent.click(
        screen.getByRole("button", { name: "Clear filters" }),
      );

      expect(statusSelect).toHaveValue("ALL");
      expect(within(testsTable()).getByText("aTest()")).toBeInTheDocument();
    });

    it("Progress and Live Focus reflect the full run even while a filter hides every test in the table", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
      });

      await userEvent.selectOptions(screen.getByLabelText("Status"), "FAILED");
      expect(
        screen.getByText("No tests match the current filters."),
      ).toBeInTheDocument();

      // Total/Running counts and the live-focus panel are untouched by the Tests table's own
      // filter - both are computed from the full, unfiltered test list.
      expect(metricValue("Total")).toBe("1");
      expect(metricValue("Running")).toBe("1");
      expect(
        within(liveFocusPanel()).getByRole("heading", {
          name: "Active now (1)",
        }),
      ).toBeInTheDocument();
      expect(within(liveFocusPanel()).getByText("aTest()")).toBeInTheDocument();
    });

    it("clicking a Live Focus test currently hidden by a filter resets the filter, reveals it, then scrolls and focuses its row", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
      });

      const statusSelect = screen.getByLabelText("Status");
      await userEvent.selectOptions(statusSelect, "FAILED");
      expect(
        screen.getByText("No tests match the current filters."),
      ).toBeInTheDocument();

      const scrollIntoView = vi.spyOn(Element.prototype, "scrollIntoView");
      await userEvent.click(
        within(liveFocusPanel()).getByRole("button", { name: /aTest\(\)/ }),
      );

      // The filter that was hiding it is reset...
      expect(statusSelect).toHaveValue("ALL");
      // ...the row is back and receives real scroll + focus.
      const row = document.getElementById("test-test-a");
      expect(within(testsTable()).getByText("aTest()")).toBeInTheDocument();
      expect(scrollIntoView).toHaveBeenCalledTimes(1);
      expect(document.activeElement).toBe(row);

      // A further, unrelated re-render (a new SSE event) must not re-trigger the reveal - it
      // already ran exactly once for this target.
      act(() => {
        client.emit(
          event({
            sequence: 2,
            type: "STEP_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
            stepId: "step-1",
            stepName: "open homepage",
          }),
        );
      });
      expect(scrollIntoView).toHaveBeenCalledTimes(1);

      scrollIntoView.mockRestore();
    });

    /**
     * Regression test (review finding, P1): `reveal()`'s own dedup previously keyed on the target
     * itself, so once a given test had been revealed once, a *second* explicit click on the same
     * Live Focus item (after the user had scrolled away) silently did nothing - every reveal after
     * the first one for that target became a no-op. Each explicit reveal is its own request and
     * must scroll/focus again, independent of whether an earlier request already targeted the same
     * row.
     */
    it("scrolls and focuses again when the same Live Focus target is explicitly clicked a second time", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
      });

      const scrollIntoView = vi.spyOn(Element.prototype, "scrollIntoView");
      await userEvent.click(
        within(liveFocusPanel()).getByRole("button", { name: /aTest\(\)/ }),
      );
      expect(scrollIntoView).toHaveBeenCalledTimes(1);

      // Move focus elsewhere, then click the very same Live Focus item a second time.
      document.getElementById("test-test-a")?.blur();
      await userEvent.click(
        within(liveFocusPanel()).getByRole("button", { name: /aTest\(\)/ }),
      );

      expect(scrollIntoView).toHaveBeenCalledTimes(2);
      expect(document.activeElement).toBe(
        document.getElementById("test-test-a"),
      );

      scrollIntoView.mockRestore();
    });

    it("a manually expanded test keeps that choice after being hidden and shown again by a filter", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () => HttpResponse.json(run())),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("QUEUED");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({
            sequence: 2,
            type: "STEP_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
            stepId: "step-1",
            stepName: "provision a room",
          }),
        );
        client.emit(
          event({
            sequence: 3,
            type: "STEP_PASSED",
            testId: "test-a",
            testDisplayName: "aTest()",
            stepId: "step-1",
            stepName: "provision a room",
          }),
        );
        client.emit(
          event({
            sequence: 4,
            type: "TEST_PASSED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({
            sequence: 5,
            type: "TEST_STARTED",
            testId: "test-b",
            testDisplayName: "bTest()",
          }),
        );
      });

      // test-a is PASSED (collapsed by default) - manually expand it.
      await userEvent.click(screen.getByRole("button", { name: "aTest()" }));
      expect(
        within(testsTable()).getByText("provision a room"),
      ).toBeInTheDocument();

      // Search for the *other* test - test-a's own row unmounts entirely.
      await userEvent.type(
        screen.getByLabelText("Search tests or steps"),
        "bTest",
      );
      expect(
        within(testsTable()).queryByText("aTest()"),
      ).not.toBeInTheDocument();

      // Clearing the search brings test-a's row back - still expanded, exactly as left it.
      await userEvent.clear(screen.getByLabelText("Search tests or steps"));
      expect(
        within(testsTable()).getByText("provision a room"),
      ).toBeInTheDocument();
    });

    it("Jump to first failure targets the first *visible* problem, never one a filter is hiding", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(run({ status: "FAILED" })),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("FAILED");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({
            sequence: 2,
            type: "TEST_FAILED",
            testId: "test-a",
            testDisplayName: "aTest()",
            detail: "boom",
          }),
        );
        client.emit(
          event({
            sequence: 3,
            type: "TEST_STARTED",
            testId: "test-b",
            testDisplayName: "bTest()",
          }),
        );
        client.emit(
          event({
            sequence: 4,
            type: "TEST_FAILED",
            testId: "test-b",
            testDisplayName: "bTest()",
            detail: "also boom",
          }),
        );
      });

      const scrollIntoView = vi.spyOn(Element.prototype, "scrollIntoView");

      // Unfiltered: targets test-a, the first failure by run order.
      await userEvent.click(
        screen.getByRole("button", { name: "Jump to first failure" }),
      );
      expect(document.activeElement).toBe(
        document.getElementById("test-test-a"),
      );

      // Search hides test-a - the button must now target test-b, the first *visible* failure,
      // never silently keep pointing at a row the filter has hidden.
      await userEvent.type(
        screen.getByLabelText("Search tests or steps"),
        "bTest",
      );
      await userEvent.click(
        screen.getByRole("button", { name: "Jump to first failure" }),
      );
      expect(document.activeElement).toBe(
        document.getElementById("test-test-b"),
      );

      scrollIntoView.mockRestore();
    });
  });

  describe("Deep links to a test or step (C4.5)", () => {
    it("reveals, scrolls to, and focuses a test-only target already present at mount", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      const scrollIntoView = vi.spyOn(Element.prototype, "scrollIntoView");
      renderPage(client, {
        initialPath: `/runs/${RUN_ID}?testId=test-a`,
      });

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
      });

      await waitFor(() =>
        expect(document.activeElement).toBe(
          document.getElementById("test-test-a"),
        ),
      );
      expect(scrollIntoView).toHaveBeenCalledTimes(1);

      // A further, unrelated re-render (a new SSE event) must not re-trigger the reveal.
      act(() => {
        client.emit(
          event({
            sequence: 2,
            type: "STEP_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
            stepId: "step-1",
            stepName: "open homepage",
          }),
        );
      });
      expect(scrollIntoView).toHaveBeenCalledTimes(1);

      scrollIntoView.mockRestore();
    });

    it("reveals a step target that arrives later over SSE, force-expanding its parent test and focusing the step's own row", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client, {
        initialPath: `/runs/${RUN_ID}?testId=test-a&stepId=step-2`,
      });

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({
            sequence: 2,
            type: "STEP_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
            stepId: "step-1",
            stepName: "open homepage",
          }),
        );
        client.emit(
          event({
            sequence: 3,
            type: "STEP_PASSED",
            testId: "test-a",
            testDisplayName: "aTest()",
            stepId: "step-1",
            stepName: "open homepage",
          }),
        );
      });

      // Not yet arrived - no crash, and no premature "not found" while the run is still going.
      expect(
        screen.queryByText(/was not found|could not be resolved/),
      ).not.toBeInTheDocument();

      // The whole rest of the test's own lifecycle arrives in one shot, ending it PASSED (not
      // RUNNING) - by the time this settles, a test's normal *default* is collapsed, not expanded.
      // Only the deep link's own force-expand (not the "RUNNING auto-expands" default this test
      // deliberately avoids relying on) is what can still be showing the target step afterward.
      act(() => {
        client.emit(
          event({
            sequence: 4,
            type: "STEP_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
            stepId: "step-2",
            stepName: "submit credentials",
          }),
        );
        client.emit(
          event({
            sequence: 5,
            type: "STEP_PASSED",
            testId: "test-a",
            testDisplayName: "aTest()",
            stepId: "step-2",
            stepName: "submit credentials",
          }),
        );
        client.emit(
          event({
            sequence: 6,
            type: "TEST_PASSED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
      });

      const stepRow = document.getElementById(
        stepRowElementId("test-a", "step-2"),
      );
      await waitFor(() => expect(document.activeElement).toBe(stepRow));
      // The parent test is now genuinely (manually) expanded - both steps are visible, not just
      // the targeted one.
      expect(screen.getByText("open homepage")).toBeInTheDocument();
    });

    it("targets the correct test's step when two different tests share the same stepId", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(run({ status: "FAILED" })),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client, {
        initialPath: `/runs/${RUN_ID}?testId=test-b&stepId=shared-step`,
      });

      await screen.findByText("FAILED");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({
            sequence: 2,
            type: "STEP_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
            stepId: "shared-step",
            stepName: "step in test a",
          }),
        );
        client.emit(
          event({
            sequence: 3,
            type: "STEP_PASSED",
            testId: "test-a",
            testDisplayName: "aTest()",
            stepId: "shared-step",
            stepName: "step in test a",
          }),
        );
        client.emit(
          event({
            sequence: 4,
            type: "TEST_PASSED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({
            sequence: 5,
            type: "TEST_STARTED",
            testId: "test-b",
            testDisplayName: "bTest()",
          }),
        );
        client.emit(
          event({
            sequence: 6,
            type: "STEP_STARTED",
            testId: "test-b",
            testDisplayName: "bTest()",
            stepId: "shared-step",
            stepName: "step in test b",
          }),
        );
        client.emit(
          event({
            sequence: 7,
            type: "STEP_FAILED",
            testId: "test-b",
            testDisplayName: "bTest()",
            stepId: "shared-step",
            stepName: "step in test b",
            detail: "boom",
          }),
        );
        client.emit(
          event({
            sequence: 8,
            type: "TEST_FAILED",
            testId: "test-b",
            testDisplayName: "bTest()",
            detail: "boom",
          }),
        );
      });

      const expectedRow = document.getElementById(
        stepRowElementId("test-b", "shared-step"),
      );
      const wrongRow = document.getElementById(
        stepRowElementId("test-a", "shared-step"),
      );
      await waitFor(() => expect(document.activeElement).toBe(expectedRow));
      expect(document.activeElement).not.toBe(wrongRow);
    });

    it("resets a filter that is hiding the deep-linked target", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client, {
        initialPath: `/runs/${RUN_ID}?testId=test-a`,
      });

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        // An unrelated test first, purely so the filter toolbar itself exists to interact with -
        // the deep-link target (test-a) has not arrived yet.
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-b",
            testDisplayName: "bTest()",
          }),
        );
      });

      // A filter already in place, applied *before* the target ever arrives - proves the deep link
      // resets it on arrival, not just that it happens to have never conflicted with one.
      await userEvent.selectOptions(screen.getByLabelText("Status"), "PASSED");
      expect(
        screen.getByText("No tests match the current filters."),
      ).toBeInTheDocument();

      // test-a arrives RUNNING, not PASSED either - still hidden by that same filter.
      act(() => {
        client.emit(
          event({
            sequence: 2,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
      });

      await waitFor(() =>
        expect(document.activeElement).toBe(
          document.getElementById("test-test-a"),
        ),
      );
      expect(screen.getByLabelText("Status")).toHaveValue("ALL");
    });

    it("shows 'Waiting for linked test result…' while the target has not yet arrived and the run is still going", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client, {
        initialPath: `/runs/${RUN_ID}?testId=test-a`,
      });

      await screen.findByText("RUNNING");
      act(() => client.open());

      expect(
        screen.getByText("Waiting for linked test result…"),
      ).toBeInTheDocument();
    });

    it("reports 'The linked test was not found in this run.' only once the run is terminal", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(run({ status: "RUNNING" })),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client, {
        initialPath: `/runs/${RUN_ID}?testId=nonexistent-test`,
      });

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
      });
      // Not terminal yet - not reported missing.
      expect(
        screen.queryByText("The linked test was not found in this run."),
      ).not.toBeInTheDocument();

      act(() => {
        client.emit(
          event({
            sequence: 2,
            type: "TEST_PASSED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({ sequence: 3, type: "RUN_FINISHED", runOutcome: "SUCCEEDED" }),
        );
      });

      expect(
        await screen.findByText("The linked test was not found in this run."),
      ).toBeInTheDocument();
    });

    it("reports 'The linked step was not found in this test.' when the test exists but the step never did", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(run({ status: "RUNNING" })),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client, {
        initialPath: `/runs/${RUN_ID}?testId=test-a&stepId=nonexistent-step`,
      });

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({
            sequence: 2,
            type: "TEST_PASSED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({ sequence: 3, type: "RUN_FINISHED", runOutcome: "SUCCEEDED" }),
        );
      });

      expect(
        await screen.findByText("The linked step was not found in this test."),
      ).toBeInTheDocument();
    });

    it("reports the result unavailable, never a not-found, once the live stream is a PROTOCOL_ERROR", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(run({ status: "RUNNING" })),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client, {
        initialPath: `/runs/${RUN_ID}?testId=test-a`,
      });

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
        client.emit(event({ sequence: 3, type: "RUN_STARTED" })); // first gap -> fresh-replay attempt
      });
      act(() => {
        client.open();
        client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
        client.emit(event({ sequence: 5, type: "RUN_STARTED" })); // second gap - budget exhausted
      });

      expect(
        await screen.findByText(
          "The linked result could not be resolved because live event data is unavailable.",
        ),
      ).toBeInTheDocument();
      expect(
        screen.queryByText("The linked test was not found in this run."),
      ).not.toBeInTheDocument();
    });

    it("never declares the target missing while RECOVERING from a gap - waits for the fresh replay instead", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client, {
        initialPath: `/runs/${RUN_ID}?testId=test-a`,
      });

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(event({ sequence: 1, type: "RUN_QUEUED" }));
        client.emit(event({ sequence: 3, type: "RUN_STARTED" })); // gap - triggers one fresh-replay attempt
      });

      expect(screen.getByRole("status")).toHaveTextContent(
        "Live stream fell out of sync. Replaying from the beginning…",
      );
      expect(
        screen.queryByText("The linked test was not found in this run."),
      ).not.toBeInTheDocument();
      expect(
        screen.getByText("Waiting for linked test result…"),
      ).toBeInTheDocument();

      // The fresh replay reconstructs state and delivers the target - it resolves normally.
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
      });
      await waitFor(() =>
        expect(document.activeElement).toBe(
          document.getElementById("test-test-a"),
        ),
      );
    });

    it("copies the correct absolute URL for a test's own link and for one of its steps", async () => {
      const writeText = vi.fn().mockResolvedValue(undefined);
      Object.defineProperty(navigator, "clipboard", {
        value: { writeText },
        configurable: true,
      });
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(
            run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
          ),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText("RUNNING");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({
            sequence: 2,
            type: "STEP_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
            stepId: "step-1",
            stepName: "open homepage",
          }),
        );
      });

      await userEvent.click(
        screen.getByRole("button", { name: "Copy link to test aTest()" }),
      );
      expect(writeText).toHaveBeenCalledWith(
        `${window.location.origin}/runs/${RUN_ID}?testId=test-a`,
      );

      await userEvent.click(
        screen.getByRole("button", { name: "Copy link to step open homepage" }),
      );
      expect(writeText).toHaveBeenCalledWith(
        `${window.location.origin}/runs/${RUN_ID}?testId=test-a&stepId=step-1`,
      );
    });

    it("navigating (e.g. browser back/forward) to a different target triggers a new one-time reveal", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(run({ status: "FAILED" })),
        ),
      );
      const client = new FakeEventStreamClient();
      const router = createMemoryRouter(
        [
          {
            path: "/runs/:runId",
            element: <RunDetailsPage eventStreamClient={client} />,
          },
        ],
        { initialEntries: [`/runs/${RUN_ID}?testId=test-a`] },
      );
      render(
        <QueryClientProvider client={createQueryClient()}>
          <RouterProvider router={router} />
        </QueryClientProvider>,
      );

      await screen.findByText("FAILED");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({
            sequence: 2,
            type: "TEST_FAILED",
            testId: "test-a",
            testDisplayName: "aTest()",
            detail: "boom",
          }),
        );
        client.emit(
          event({
            sequence: 3,
            type: "TEST_STARTED",
            testId: "test-b",
            testDisplayName: "bTest()",
          }),
        );
        client.emit(
          event({
            sequence: 4,
            type: "TEST_FAILED",
            testId: "test-b",
            testDisplayName: "bTest()",
            detail: "also boom",
          }),
        );
      });
      await waitFor(() =>
        expect(document.activeElement).toBe(
          document.getElementById("test-test-a"),
        ),
      );

      act(() => {
        void router.navigate(`/runs/${RUN_ID}?testId=test-b`);
      });

      await waitFor(() =>
        expect(document.activeElement).toBe(
          document.getElementById("test-test-b"),
        ),
      );
    });

    /**
     * Regression test (review finding, P2): `deepLinkHandledKeyRef` previously never reset once a
     * target had been handled, so navigating away from it (the URL briefly carrying no target at
     * all) and then back to the *exact same* target left it permanently treated as "already
     * handled" - a real browser Back/Forward round trip to the same link would silently never
     * reveal/focus it again.
     */
    it("re-triggers reveal/focus after the target briefly disappears from the URL and then comes back", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(run({ status: "FAILED" })),
        ),
      );
      const client = new FakeEventStreamClient();
      const router = createMemoryRouter(
        [
          {
            path: "/runs/:runId",
            element: <RunDetailsPage eventStreamClient={client} />,
          },
        ],
        { initialEntries: [`/runs/${RUN_ID}?testId=test-a`] },
      );
      render(
        <QueryClientProvider client={createQueryClient()}>
          <RouterProvider router={router} />
        </QueryClientProvider>,
      );

      await screen.findByText("FAILED");
      act(() => {
        client.open();
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
        client.emit(
          event({
            sequence: 2,
            type: "TEST_FAILED",
            testId: "test-a",
            testDisplayName: "aTest()",
            detail: "boom",
          }),
        );
      });
      await waitFor(() =>
        expect(document.activeElement).toBe(
          document.getElementById("test-test-a"),
        ),
      );

      document.getElementById("test-test-a")?.blur();
      act(() => {
        void router.navigate(`/runs/${RUN_ID}`); // no target at all
      });
      expect(document.activeElement).not.toBe(
        document.getElementById("test-test-a"),
      );

      const scrollIntoView = vi.spyOn(Element.prototype, "scrollIntoView");
      act(() => {
        void router.navigate(`/runs/${RUN_ID}?testId=test-a`); // back to the very same target
      });

      await waitFor(() =>
        expect(document.activeElement).toBe(
          document.getElementById("test-test-a"),
        ),
      );
      expect(scrollIntoView).toHaveBeenCalled();
      scrollIntoView.mockRestore();
    });

    /**
     * Regression test (review finding, P1): on a fresh deep-link load against an already-finished
     * run, the REST `GET /runs/:id` response routinely resolves *before* the SSE replay has
     * delivered every event. Gating "not found" on a REST-derived terminal boolean (rather than the
     * stream's own `CLOSED` state) reported the target missing while it was, in fact, only a few
     * replayed events away.
     */
    it("stays 'waiting', never a premature not-found, while an already-terminal REST snapshot resolves ahead of the SSE replay", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(run({ status: "FAILED" })),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client, {
        initialPath: `/runs/${RUN_ID}?testId=test-a`,
      });

      // The REST response (terminal: FAILED) has already resolved, but the stream hasn't even
      // opened yet - connectionState is CONNECTING, not CLOSED.
      await screen.findByText("FAILED");
      expect(
        screen.getByText("Waiting for linked test result…"),
      ).toBeInTheDocument();
      expect(
        screen.queryByText("The linked test was not found in this run."),
      ).not.toBeInTheDocument();

      act(() => client.open());
      // Now LIVE, but the target still has not arrived over the replay - still waiting, not
      // reported missing.
      expect(
        screen.getByText("Waiting for linked test result…"),
      ).toBeInTheDocument();
      expect(
        screen.queryByText("The linked test was not found in this run."),
      ).not.toBeInTheDocument();

      act(() => {
        client.emit(
          event({
            sequence: 1,
            type: "TEST_STARTED",
            testId: "test-a",
            testDisplayName: "aTest()",
          }),
        );
      });

      await waitFor(() =>
        expect(document.activeElement).toBe(
          document.getElementById("test-test-a"),
        ),
      );
      expect(
        screen.queryByText("The linked test was not found in this run."),
      ).not.toBeInTheDocument();
    });

    /**
     * Regression test (review finding, P2): `parseRunResultTarget` previously collapsed every
     * malformed link (a `stepId` with no `testId`, a blank `testId`, a blank `stepId`) into the
     * same bare `undefined` an entirely absent target also produced - a viewer following a broken
     * link got an ordinary, unexplained page instead of any indication the URL itself was invalid.
     */
    it("shows 'This result link is invalid.' for a malformed deep link", async () => {
      server.use(
        http.get("/api/v1/runs/:runId", () =>
          HttpResponse.json(run({ status: "RUNNING" })),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client, {
        initialPath: `/runs/${RUN_ID}?stepId=s-1`, // stepId with no testId
      });

      await screen.findByText("RUNNING");
      expect(
        await screen.findByText("This result link is invalid."),
      ).toBeInTheDocument();
    });

    /**
     * Regression test (review finding, P2): the panel previously hid only on `connectionState`, so
     * an unknown run (e.g. a 404) - where `runStatus` is `undefined` because there is no REST data
     * to read a status from at all - could still render "Active now (0)", implying a run this
     * dashboard cannot even confirm exists might have something active.
     */
    it("hides the Live Focus panel entirely while the run itself is unknown (e.g. a 404)", async () => {
      server.use(
        http.get(
          "/api/v1/runs/:runId",
          () => new HttpResponse(null, { status: 404 }),
        ),
      );
      const client = new FakeEventStreamClient();
      renderPage(client);

      await screen.findByText(/This run is no longer available/);
      expect(screen.queryByRole("region")).not.toBeInTheDocument();
    });
  });
});
