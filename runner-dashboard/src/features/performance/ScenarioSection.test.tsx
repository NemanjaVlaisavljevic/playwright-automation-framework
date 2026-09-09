import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { Scenario } from "../../domain/performance-baseline";
import { ScenarioSection } from "./ScenarioSection";

describe("ScenarioSection", () => {
  it("shows which specific metric regressed via a visible Result column, not color alone", () => {
    const scenario: Scenario = {
      scenarioId: "public-read",
      displayName: "Public read endpoints",
      status: "REGRESSION",
      latencies: [
        {
          metricId: "runs-list",
          displayName: "Runs list",
          sampleCount: 30,
          p50Ms: 40,
          p95Ms: 900,
          p99Ms: 950,
          p95LimitMs: 750,
          passed: false,
        },
        {
          metricId: "capabilities",
          displayName: "Capabilities",
          sampleCount: 30,
          p50Ms: 4,
          p95Ms: 5,
          p99Ms: 6,
          p95LimitMs: 250,
          passed: true,
        },
      ],
      signals: [
        {
          metricId: "unexpected-error-rate",
          displayName: "Unexpected error rate",
          unit: "rate",
          value: 0.5,
          passed: false,
        },
      ],
    };

    render(<ScenarioSection scenario={scenario} />);

    // The scenario-level badge alone can only say "something regressed" - the Result column is
    // what tells a viewer *which* row, in both tables, even the signals table (which has no
    // numeric limit to compare against at all).
    const runsListRow = screen.getByText("Runs list").closest("tr");
    expect(runsListRow).not.toBeNull();
    expect(
      within(runsListRow as HTMLElement).getByText("REGRESSION"),
    ).toBeInTheDocument();
    // 900/750 rounds to 120% - a regression genuinely can exceed 100% utilization.
    expect(
      within(runsListRow as HTMLElement).getByText("120%"),
    ).toBeInTheDocument();

    const capabilitiesRow = screen.getByText("Capabilities").closest("tr");
    expect(runsListRow).not.toBe(capabilitiesRow);
    expect(
      within(capabilitiesRow as HTMLElement).getByText("PASSED"),
    ).toBeInTheDocument();

    const errorRateRow = screen
      .getByText("Unexpected error rate")
      .closest("tr");
    expect(
      within(errorRateRow as HTMLElement).getByText("REGRESSION"),
    ).toBeInTheDocument();
    expect(
      within(errorRateRow as HTMLElement).getByText("50.0%"),
    ).toBeInTheDocument();
  });

  it("marks a genuinely un-gated metric as OBSERVED ONLY, never PASSED or REGRESSION", () => {
    const scenario: Scenario = {
      scenarioId: "create-run",
      displayName: "Create run (admin)",
      status: "PASSED",
      latencies: [
        {
          metricId: "create-run",
          displayName: "Create run (202 Accepted)",
          sampleCount: 1,
          p50Ms: 148,
          p95Ms: 148,
          p99Ms: 148,
          p95LimitMs: null,
          passed: null,
        },
      ],
      signals: [],
    };

    render(<ScenarioSection scenario={scenario} />);

    const row = screen.getByText("Create run (202 Accepted)").closest("tr");
    expect(
      within(row as HTMLElement).getByText("OBSERVED ONLY"),
    ).toBeInTheDocument();
    // Both the Limit and Utilization columns render "—" for a genuinely un-gated metric.
    expect(within(row as HTMLElement).getAllByText("—")).toHaveLength(2);
  });

  it("omits the latency table entirely when a scenario has no latency metrics", () => {
    const scenario: Scenario = {
      scenarioId: "sse-connection-cap",
      displayName: "SSE per-IP connection cap",
      status: "PASSED",
      latencies: [],
      signals: [
        {
          metricId: "accepted-connections",
          displayName: "Accepted connections",
          unit: "count",
          value: 3,
          passed: true,
        },
      ],
    };

    render(<ScenarioSection scenario={scenario} />);

    expect(screen.queryByText("Limit (p95)")).not.toBeInTheDocument();
    expect(screen.getByText("Accepted connections")).toBeInTheDocument();
  });
});
