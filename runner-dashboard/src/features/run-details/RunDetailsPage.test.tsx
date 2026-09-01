import { QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { Link, MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/query-client";
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

function renderPage(client: FakeEventStreamClient) {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={[`/runs/${RUN_ID}`]}>
        <Routes>
          <Route
            path="/runs/:runId"
            element={<RunDetailsPage eventStreamClient={client} />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function event(overrides: Record<string, unknown>): string {
  return JSON.stringify({
    schemaVersion: "1.0",
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
