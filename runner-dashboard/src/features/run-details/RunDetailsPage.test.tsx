import { QueryClientProvider } from "@tanstack/react-query";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { Link, MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/query-client";
import { CURRENT_SCHEMA_VERSION } from "../../domain/runner-event";
import { server } from "../../test/msw/server";
import { FakeEventStreamClient } from "../event-stream/fake-event-stream-client";
import { RunDetailsPage } from "./RunDetailsPage";

const RUN_ID = "run-1";

function run(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    runId: RUN_ID,
    environment: "PUBLIC",
    suite: "SMOKE",
    status: "QUEUED",
    requestedAt: "2026-09-01T10:00:00Z",
    processLogUrl: `/api/v1/runs/${RUN_ID}/log`,
    ...overrides,
  };
}

function renderPage(
  client: FakeEventStreamClient,
  options: { runPollIntervalMs?: number } = {},
) {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={[`/runs/${RUN_ID}`]}>
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
  return screen.getByText(label).previousElementSibling?.textContent ?? null;
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
      await screen.findByRole("link", { name: "Download trace" }),
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
      await screen.findByRole("link", { name: "Download trace" }),
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
    // stepId.
    expect(
      await screen.findAllByRole("link", { name: "Download trace" }),
    ).toHaveLength(1);
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

    // No click at all - the row is open purely because the test is still RUNNING.
    expect(screen.getByText("open homepage")).toBeInTheDocument();

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
    expect(screen.queryByText("open homepage")).not.toBeInTheDocument();
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
    expect(screen.getByText("open homepage")).toBeInTheDocument();

    // An explicit choice while still RUNNING - overriding the auto-open default.
    await userEvent.click(screen.getByRole("button", { name: "aTest()" }));
    expect(screen.queryByText("open homepage")).not.toBeInTheDocument();

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
    expect(screen.queryByText("open homepage")).not.toBeInTheDocument();
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
});
