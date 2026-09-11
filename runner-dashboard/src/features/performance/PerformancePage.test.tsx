import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/query-client";
import { server } from "../../test/msw/server";
import { PerformancePage } from "./PerformancePage";

const VALID_BASELINE = {
  schemaVersion: 1,
  generatedAt: "2026-06-01T12:00:00.000Z",
  source: {
    measuredAt: "2026-01-01T00:00:00Z",
    commitSha: "abc123def456abc123def456abc123def4567890",
    profile: "production-policy",
    executionMode: "cold",
    repetitions: 1,
    runnerImage: "ubuntu-latest",
    k6Version: "1.5.0",
    workflowRun: {
      id: 42,
      attempt: 1,
      url: "https://github.com/example/example/actions/runs/42",
    },
  },
  scenarios: [
    {
      scenarioId: "health",
      displayName: "Health checks",
      status: "PASSED",
      latencies: [
        {
          metricId: "liveness",
          displayName: "Liveness",
          sampleCount: 30,
          p50Ms: 3.5,
          p95Ms: 5.0,
          p99Ms: 6.0,
          p95LimitMs: 50,
          passed: true,
        },
      ],
      signals: [
        {
          metricId: "unexpected-429",
          displayName: "Unexpected throttled responses",
          unit: "count",
          value: 0,
          passed: null,
        },
      ],
    },
  ],
};

function renderPage() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter>
        <PerformancePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("PerformancePage", () => {
  it("always shows the 'not live monitoring' disclaimer, even before data loads", () => {
    renderPage();

    const disclaimer = screen.getByText(/not live monitoring, no SLA/);
    expect(disclaimer).toBeInTheDocument();
    // Static callout, not an assertive role="alert" live region.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("renders scenario status, latency table, and source provenance on success", async () => {
    server.use(
      http.get("/performance/baseline.json", () =>
        HttpResponse.json(VALID_BASELINE),
      ),
    );

    renderPage();

    const heading = await screen.findByRole("heading", {
      name: "Health checks",
    });
    expect(heading).toBeInTheDocument();

    // Scoped to the header row, not the full card, to avoid matching a later "PASSED" table badge.
    const scenarioHeader = heading.closest("div");
    expect(scenarioHeader).not.toBeNull();
    expect(
      within(scenarioHeader as HTMLElement).getByText("PASSED"),
    ).toBeInTheDocument();

    const latencyRow = screen.getByText("Liveness").closest("tr");
    expect(
      within(latencyRow as HTMLElement).getByText("5.0 ms"),
    ).toBeInTheDocument();
    expect(
      within(latencyRow as HTMLElement).getByText("10%"),
    ).toBeInTheDocument();
    expect(
      within(latencyRow as HTMLElement).getByText("PASSED"),
    ).toBeInTheDocument();

    const signalRow = screen
      .getByText("Unexpected throttled responses")
      .closest("tr");
    expect(
      within(signalRow as HTMLElement).getByText("OBSERVED ONLY"),
    ).toBeInTheDocument();

    expect(screen.getByText("Regressed scenarios")).toBeInTheDocument();

    expect(screen.getByText("abc123d")).toBeInTheDocument();
    expect(screen.getByText("Published")).toBeInTheDocument();
    expect(screen.getByText("Repetitions")).toBeInTheDocument();
    const runLink = screen.getByRole("link", { name: /Run #42/ });
    expect(runLink).toHaveAttribute(
      "href",
      "https://github.com/example/example/actions/runs/42",
    );
  });

  it("shows a controlled error, not a crash, when the baseline is unreachable", async () => {
    server.use(
      http.get("/performance/baseline.json", () => HttpResponse.error()),
    );

    renderPage();

    expect(
      await screen.findByText(/Could not load the performance baseline/),
    ).toBeInTheDocument();
  });

  it("shows a controlled error, not a crash, when the baseline violates its own contract", async () => {
    server.use(
      http.get("/performance/baseline.json", () =>
        HttpResponse.json({ not: "a baseline" }),
      ),
    );

    renderPage();

    expect(
      await screen.findByText(/Could not load the performance baseline/),
    ).toBeInTheDocument();
  });
});
