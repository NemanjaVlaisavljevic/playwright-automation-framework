import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/query-client";
import { queryKeys } from "../../api/query-keys";
import { server } from "../../test/msw/server";
import { RunsTable } from "./RunsTable";

function renderTable(props: Partial<{ pollIntervalMs: number }> = {}) {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter>
        <RunsTable {...props} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const run = (overrides: Partial<Record<string, unknown>> = {}) => {
  const runId = (overrides.runId as string | undefined) ?? "run-1";
  return {
    runId,
    environment: "PUBLIC",
    suite: "SMOKE",
    status: "QUEUED",
    requestedAt: "2026-09-01T10:00:00Z",
    processLogUrl: `/api/v1/runs/${runId}/log`,
    selectedTests: [],
    artifactsPurged: false,
    ...overrides,
  };
};

describe("RunsTable", () => {
  it("shows a loading state, then an empty state when there are no runs", async () => {
    server.use(http.get("/api/v1/runs", () => HttpResponse.json([])));

    renderTable();

    expect(screen.getByText("Loading runs…")).toBeInTheDocument();
    expect(await screen.findByText("No runs yet.")).toBeInTheDocument();
  });

  it("shows a clear error when the runs list can't be loaded", async () => {
    server.use(http.get("/api/v1/runs", () => HttpResponse.error()));

    renderTable();

    expect(
      await screen.findByText(
        "Could not load runs: Could not reach the runner service.",
      ),
    ).toBeInTheDocument();
  });

  it("renders a row per run with a View link, and Cancel/Download only where applicable", async () => {
    server.use(
      http.get("/api/v1/runs", () =>
        HttpResponse.json([
          run({ runId: "run-queued", status: "QUEUED" }),
          // STARTING but not yet startedAt: no Download log link either, same as QUEUED.
          run({ runId: "run-starting", status: "STARTING" }),
          run({
            runId: "run-running",
            status: "RUNNING",
            startedAt: "2026-09-01T10:00:05Z",
          }),
          run({
            runId: "run-done",
            status: "SUCCEEDED",
            startedAt: "2026-09-01T10:00:05Z",
            finishedAt: "2026-09-01T10:01:05Z",
          }),
          // Cancelled while still QUEUED - terminal, but startedAt was never set.
          run({ runId: "run-cancelled-early", status: "CANCELLED" }),
        ]),
      ),
    );

    renderTable();

    const rows = await screen.findAllByRole("row");
    // header + 5 data rows
    expect(rows).toHaveLength(6);

    const queuedRow = within(rows[1]!);
    expect(queuedRow.getByRole("link", { name: "View" })).toHaveAttribute(
      "href",
      "/runs/run-queued",
    );
    expect(
      queuedRow.getByRole("button", { name: "Cancel" }),
    ).toBeInTheDocument();
    expect(
      queuedRow.queryByRole("link", { name: "Download log" }),
    ).not.toBeInTheDocument();
    expect(queuedRow.getByText("—")).toBeInTheDocument();

    const startingRow = within(rows[2]!);
    expect(
      startingRow.getByRole("button", { name: "Cancel" }),
    ).toBeInTheDocument();
    expect(
      startingRow.queryByRole("link", { name: "Download log" }),
    ).not.toBeInTheDocument();

    const runningRow = within(rows[3]!);
    expect(
      runningRow.getByRole("button", { name: "Cancel" }),
    ).toBeInTheDocument();
    expect(
      runningRow.getByRole("link", { name: "Download log" }),
    ).toHaveAttribute("href", "/api/v1/runs/run-running/log");

    const doneRow = within(rows[4]!);
    expect(
      doneRow.queryByRole("button", { name: "Cancel" }),
    ).not.toBeInTheDocument();
    expect(
      doneRow.getByRole("link", { name: "Download log" }),
    ).toBeInTheDocument();
    expect(doneRow.getByText("1m 0s")).toBeInTheDocument();

    const cancelledEarlyRow = within(rows[5]!);
    expect(
      cancelledEarlyRow.queryByRole("button", { name: "Cancel" }),
    ).not.toBeInTheDocument();
    expect(
      cancelledEarlyRow.queryByRole("link", { name: "Download log" }),
    ).not.toBeInTheDocument();
  });

  it("cancels a run and refreshes the list", async () => {
    const user = userEvent.setup();
    let cancelled = false;
    server.use(
      http.get("/api/v1/runs", () =>
        HttpResponse.json([
          cancelled ? run({ status: "CANCELLED" }) : run({ status: "QUEUED" }),
        ]),
      ),
      http.post("/api/v1/runs/:runId/cancel", () => {
        cancelled = true;
        return HttpResponse.json(run({ status: "CANCELLED" }));
      }),
    );

    renderTable();

    await user.click(await screen.findByRole("button", { name: "Cancel" }));

    // A "cell" role query, not getByText: the Status filter's own <option> for each distinct
    // status value in the data is *also* text-matched by "CANCELLED" once this run reaches that
    // status, so a plain getByText would ambiguously match both.
    await waitFor(async () => {
      expect(
        await screen.findByRole("cell", { name: "CANCELLED" }),
      ).toBeInTheDocument();
    });
  });

  it("tracks two concurrent cancellations independently, without a shared mutation state", async () => {
    const user = userEvent.setup();
    let cancelledA = false;
    let cancelledB = false;
    let resolveA: (() => void) | undefined;

    server.use(
      http.get("/api/v1/runs", () =>
        HttpResponse.json([
          run({ runId: "run-a", status: cancelledA ? "CANCELLED" : "QUEUED" }),
          run({ runId: "run-b", status: cancelledB ? "CANCELLED" : "QUEUED" }),
        ]),
      ),
      http.post(
        "/api/v1/runs/run-a/cancel",
        () =>
          new Promise<Response>((resolve) => {
            resolveA = () => {
              cancelledA = true;
              resolve(
                HttpResponse.json(
                  run({ runId: "run-a", status: "CANCELLED" }),
                ) as unknown as Response,
              );
            };
          }),
      ),
      http.post("/api/v1/runs/run-b/cancel", () => {
        cancelledB = true;
        return HttpResponse.json(run({ runId: "run-b", status: "CANCELLED" }));
      }),
    );

    renderTable();

    const rows = await screen.findAllByRole("row");
    expect(rows).toHaveLength(3);
    const rowA = within(rows[1]!);
    const rowB = within(rows[2]!);

    await user.click(rowA.getByRole("button", { name: "Cancel" }));
    expect(rowA.getByRole("button", { name: "Cancel" })).toBeDisabled();

    // Proves cancels are independent per row, not sharing one mutation's state.
    await user.click(rowB.getByRole("button", { name: "Cancel" }));
    expect(rowA.getByRole("button", { name: "Cancel" })).toBeDisabled();

    resolveA?.();
    await waitFor(() =>
      expect(screen.getAllByText("CANCELLED")).toHaveLength(2),
    );
  });

  it("recovers automatically once the backend comes back, without remounting", async () => {
    server.use(http.get("/api/v1/runs", () => HttpResponse.error()));

    renderTable({ pollIntervalMs: 20 });

    expect(
      await screen.findByText(
        "Could not load runs: Could not reach the runner service.",
      ),
    ).toBeInTheDocument();

    // Restores the default handler; only the next poll picks it up, nothing remounts.
    server.resetHandlers();

    expect(await screen.findByText("No runs yet.")).toBeInTheDocument();
  });

  it("stops polling once every run has reached a terminal status", async () => {
    let requestCount = 0;
    server.use(
      http.get("/api/v1/runs", () => {
        requestCount += 1;
        return HttpResponse.json([
          run({ status: "SUCCEEDED", finishedAt: "2026-09-01T10:01:00Z" }),
        ]);
      }),
    );

    renderTable({ pollIntervalMs: 20 });

    // "cell" role, not getByText: the Status filter's own <option> also matches "SUCCEEDED" here.
    await screen.findByRole("cell", { name: "SUCCEEDED" });
    const countAfterFirstLoad = requestCount;

    await new Promise((resolve) => setTimeout(resolve, 100));

    expect(requestCount).toBe(countAfterFirstLoad);
  });

  it("keeps polling while any run is non-terminal", async () => {
    let requestCount = 0;
    server.use(
      http.get("/api/v1/runs", () => {
        requestCount += 1;
        return HttpResponse.json([
          run({ status: "RUNNING", startedAt: "2026-09-01T10:00:00Z" }),
        ]);
      }),
    );

    renderTable({ pollIntervalMs: 20 });

    // "cell" role, not getByText: the Status filter's own <option> also matches "RUNNING" here.
    await screen.findByRole("cell", { name: "RUNNING" });
    await waitFor(() => expect(requestCount).toBeGreaterThan(1));
  });

  it("filters rows by a case-insensitive runId search", async () => {
    const user = userEvent.setup();
    server.use(
      http.get("/api/v1/runs", () =>
        HttpResponse.json([
          run({ runId: "run-alpha" }),
          run({ runId: "run-beta" }),
        ]),
      ),
    );

    renderTable();
    await screen.findAllByRole("row");

    await user.type(screen.getByLabelText("Search by run ID"), "ALPHA");

    expect(screen.getByRole("link", { name: "View" })).toHaveAttribute(
      "href",
      "/runs/run-alpha",
    );
    expect(screen.queryByText("run-beta")).not.toBeInTheDocument();
  });

  it("filters rows by status", async () => {
    const user = userEvent.setup();
    server.use(
      http.get("/api/v1/runs", () =>
        HttpResponse.json([
          run({ runId: "run-queued", status: "QUEUED" }),
          run({ runId: "run-done", status: "SUCCEEDED" }),
        ]),
      ),
    );

    renderTable();
    await screen.findByRole("cell", { name: "QUEUED" });

    await user.selectOptions(screen.getByLabelText("Status"), "SUCCEEDED");

    expect(screen.getByRole("cell", { name: "SUCCEEDED" })).toBeInTheDocument();
    expect(
      screen.queryByRole("cell", { name: "QUEUED" }),
    ).not.toBeInTheDocument();
  });

  it("keeps a selected status filter honest once its matching run disappears from a later poll", async () => {
    const user = userEvent.setup();
    let finished = false;
    server.use(
      http.get("/api/v1/runs", () =>
        HttpResponse.json([
          run({
            status: finished ? "SUCCEEDED" : "RUNNING",
            startedAt: "2026-09-01T10:00:00Z",
            ...(finished ? { finishedAt: "2026-09-01T10:01:00Z" } : {}),
          }),
        ]),
      ),
    );

    renderTable({ pollIntervalMs: 20 });
    await user.selectOptions(await screen.findByLabelText("Status"), "RUNNING");
    expect(screen.getByRole("cell", { name: "RUNNING" })).toBeInTheDocument();

    // The filter must not silently change to "All" once no run matches it.
    finished = true;

    await waitFor(() => {
      expect(
        screen.getByText("No runs match the current filters."),
      ).toBeInTheDocument();
    });
    expect(screen.getByLabelText("Status")).toHaveValue("RUNNING");
  });

  it("does not silently re-arm a status filter once a matching run reappears - it never actually left", async () => {
    const user = userEvent.setup();
    const queryClient = createQueryClient();
    let poll = 1;
    server.use(
      http.get("/api/v1/runs", () => {
        if (poll === 1) {
          return HttpResponse.json([
            run({
              runId: "run-1",
              status: "RUNNING",
              startedAt: "2026-09-01T10:00:00Z",
            }),
          ]);
        }
        if (poll === 2) {
          return HttpResponse.json([
            run({
              runId: "run-1",
              status: "SUCCEEDED",
              startedAt: "2026-09-01T10:00:00Z",
              finishedAt: "2026-09-01T10:01:00Z",
            }),
          ]);
        }
        return HttpResponse.json([
          run({
            runId: "run-1",
            status: "SUCCEEDED",
            startedAt: "2026-09-01T10:00:00Z",
            finishedAt: "2026-09-01T10:01:00Z",
          }),
          run({
            runId: "run-2",
            status: "RUNNING",
            startedAt: "2026-09-01T10:02:00Z",
          }),
        ]);
      }),
    );

    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <RunsTable pollIntervalMs={20} />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    // Poll 1: select the RUNNING filter.
    await user.selectOptions(await screen.findByLabelText("Status"), "RUNNING");
    expect(screen.getByRole("cell", { name: "RUNNING" })).toBeInTheDocument();

    // Poll 2: run-1 finished - every run is now terminal, so polling stops on its own.
    poll = 2;
    await waitFor(() => {
      expect(
        screen.getByText("No runs match the current filters."),
      ).toBeInTheDocument();
    });
    expect(screen.getByLabelText("Status")).toHaveValue("RUNNING");

    // Poll 3: a new run starts RUNNING, simulated via the same invalidation a real launch triggers.
    poll = 3;
    await queryClient.invalidateQueries({ queryKey: queryKeys.runs });

    // The filter was never cleared, so it applies naturally - not a "silent reactivation".
    expect(
      await screen.findByRole("cell", { name: "RUNNING" }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Status")).toHaveValue("RUNNING");
    expect(screen.getAllByRole("row")).toHaveLength(2); // header + the one RUNNING row, run-1 excluded
  });

  it("filters rows by suite", async () => {
    const user = userEvent.setup();
    server.use(
      http.get("/api/v1/runs", () =>
        HttpResponse.json([
          run({ runId: "run-smoke", suite: "SMOKE" }),
          run({ runId: "run-regression", suite: "REGRESSION" }),
        ]),
      ),
    );

    renderTable();
    await screen.findAllByRole("row");

    await user.selectOptions(screen.getByLabelText("Suite"), "REGRESSION");

    expect(screen.getByRole("link", { name: "View" })).toHaveAttribute(
      "href",
      "/runs/run-regression",
    );
    expect(screen.getAllByRole("link", { name: "View" })).toHaveLength(1);
  });

  it("shows a distinct empty state when filters/search match nothing", async () => {
    const user = userEvent.setup();
    server.use(http.get("/api/v1/runs", () => HttpResponse.json([run()])));

    renderTable();
    await screen.findAllByRole("row");

    await user.type(screen.getByLabelText("Search by run ID"), "no-such-run");

    expect(
      screen.getByText("No runs match the current filters."),
    ).toBeInTheDocument();
  });

  it("sorts rows by a column, toggling direction on repeated clicks", async () => {
    const user = userEvent.setup();
    server.use(
      http.get("/api/v1/runs", () =>
        HttpResponse.json([
          run({ runId: "run-b", suite: "UI" }),
          run({ runId: "run-a", suite: "API" }),
        ]),
      ),
    );

    renderTable();
    const initialRows = await screen.findAllByRole("row");
    // Default (unsorted) order matches the server response.
    expect(within(initialRows[1]!).getByText("UI")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Suite" }));
    const ascendingRows = screen.getAllByRole("row");
    expect(within(ascendingRows[1]!).getByText("API")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: /Suite/ })).toHaveAttribute(
      "aria-sort",
      "ascending",
    );

    await user.click(screen.getByRole("button", { name: "Suite" }));
    const descendingRows = screen.getAllByRole("row");
    expect(within(descendingRows[1]!).getByText("UI")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: /Suite/ })).toHaveAttribute(
      "aria-sort",
      "descending",
    );
  });
});
