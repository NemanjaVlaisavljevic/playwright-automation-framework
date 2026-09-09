import { describe, expect, it } from "vitest";
import baselineJson from "../../public/performance/baseline.json";
import { PerformanceBaseline } from "./performance-baseline";

/**
 * D4.4.3b - contract-validation test: proves the actually-committed `public/performance/
 * baseline.json` parses through the real schema, field-for-field. This is deliberately separate
 * from `summarize-baseline-core.test.mjs`'s own golden-output test (which proves the *generator* is
 * correct/deterministic against a small fixture, independent of whatever the real file currently
 * contains) - this test instead proves the *currently committed* file is valid, independent of
 * whether the generator that produced it has since changed. Importing the real file directly (not a
 * fixture) is deliberate: a future edit to `baseline.json` that doesn't go through
 * `summarize-baseline.mjs` - or a schema change here that the real file no longer satisfies - must
 * fail this test immediately.
 */
describe("PerformanceBaseline - contract validation", () => {
  it("parses the committed public/performance/baseline.json", () => {
    const result = PerformanceBaseline.safeParse(baselineJson);

    expect(
      result.success,
      result.success ? "" : JSON.stringify(result.error?.issues, null, 2),
    ).toBe(true);
  });

  it("every scenario reports a real, non-empty displayName and at least one metric", () => {
    const baseline = PerformanceBaseline.parse(baselineJson);

    for (const scenario of baseline.scenarios) {
      expect(scenario.displayName.trim().length).toBeGreaterThan(0);
      expect(
        scenario.latencies.length + scenario.signals.length,
      ).toBeGreaterThan(0);
    }
  });

  it("every required (thresholdRequired) metric reports passed:true, never null or false", () => {
    // A schema-level sanity check mirroring the generator's own fail-closed intent: this file can
    // only ever be committed from a run where every gated metric genuinely passed, so `passed`
    // should never be anything but `true` or `null` (the latter only for the small, deliberately
    // ungated set - see scenario-configs.mjs's own `thresholdRequired: false` metrics).
    const baseline = PerformanceBaseline.parse(baselineJson);

    for (const scenario of baseline.scenarios) {
      for (const metric of [...scenario.latencies, ...scenario.signals]) {
        expect(
          metric.passed,
          `${scenario.scenarioId}.${metric.metricId}`,
        ).not.toBe(false);
      }
    }
  });
});

describe("PerformanceBaseline - schema rejection", () => {
  function validBaseline() {
    const scenario = {
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
          p95LimitMs: 50 as number | null,
          passed: true as boolean | null,
        },
      ],
      signals: [
        {
          metricId: "unexpected-429",
          displayName: "Unexpected throttled responses",
          unit: "count",
          value: 0,
          passed: true,
        },
      ],
    };
    return {
      baseline: {
        schemaVersion: 1,
        generatedAt: "2026-06-01T12:00:00.000Z",
        source: {
          measuredAt: "2026-01-01T00:00:00Z",
          commitSha: "000000000000000000000000000000000000dead",
          profile: "production-policy",
          executionMode: "cold",
          repetitions: 1,
          runnerImage: "ubuntu-latest",
          k6Version: "1.5.0",
          workflowRun: {
            id: 1,
            attempt: 1,
            url: "https://github.com/example/example/actions/runs/1",
          },
        },
        scenarios: [scenario],
      },
      scenario,
    };
  }

  it("accepts a minimal, well-formed, internally-consistent baseline (sanity check for the fixture itself)", () => {
    const result = PerformanceBaseline.safeParse(validBaseline().baseline);
    expect(
      result.success,
      result.success ? "" : JSON.stringify(result.error?.issues, null, 2),
    ).toBe(true);
  });

  it("rejects an unknown top-level field (.strict())", () => {
    const { baseline } = validBaseline();
    const broken = { ...baseline, extraField: "not allowed" };
    expect(PerformanceBaseline.safeParse(broken).success).toBe(false);
  });

  it("rejects an unknown field inside a scenario (.strict())", () => {
    const { baseline, scenario } = validBaseline();
    (scenario as Record<string, unknown>).extraField = "not allowed";
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects a commitSha that is not exactly 40 hex characters", () => {
    const { baseline } = validBaseline();
    baseline.source.commitSha = "deadbeef";
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects an invalid profile enum value", () => {
    const { baseline } = validBaseline();
    baseline.source.profile = "staging";
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects a Markdown link instead of a plain URL for workflowRun.url", () => {
    const { baseline } = validBaseline();
    baseline.source.workflowRun.url =
      "[see run](https://github.com/example/example/actions/runs/1)";
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects a workflowRun.url on a host other than github.com", () => {
    const { baseline } = validBaseline();
    baseline.source.workflowRun.url = "https://evil.example/actions/runs/1";
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects a workflowRun.url using http instead of https", () => {
    const { baseline } = validBaseline();
    baseline.source.workflowRun.url =
      "http://github.com/example/example/actions/runs/1";
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects a workflowRun.url whose own embedded run id does not match workflowRun.id", () => {
    const { baseline } = validBaseline();
    baseline.source.workflowRun.url =
      "https://github.com/example/example/actions/runs/999";
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects a workflowRun.url carrying a query string", () => {
    const { baseline } = validBaseline();
    baseline.source.workflowRun.url =
      "https://github.com/example/example/actions/runs/1?tab=summary";
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects a workflowRun.url carrying a hash fragment", () => {
    const { baseline } = validBaseline();
    baseline.source.workflowRun.url =
      "https://github.com/example/example/actions/runs/1#step:1:1";
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects a workflowRun.url carrying credentials", () => {
    const { baseline } = validBaseline();
    baseline.source.workflowRun.url =
      "https://user:pass@github.com/example/example/actions/runs/1";
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects percentile ordering violations (p95 > p99)", () => {
    const { baseline, scenario } = validBaseline();
    const [latency] = scenario.latencies;
    if (!latency)
      throw new Error("fixture bug: expected at least one latency metric");
    latency.p95Ms = 100;
    latency.p99Ms = 6.0;
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects p95LimitMs set with passed left null", () => {
    const { baseline, scenario } = validBaseline();
    const [latency] = scenario.latencies;
    if (!latency)
      throw new Error("fixture bug: expected at least one latency metric");
    latency.passed = null;
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects passed set with p95LimitMs left null", () => {
    const { baseline, scenario } = validBaseline();
    const [latency] = scenario.latencies;
    if (!latency)
      throw new Error("fixture bug: expected at least one latency metric");
    latency.p95LimitMs = null;
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects passed:true when p95Ms is not actually below p95LimitMs", () => {
    const { baseline, scenario } = validBaseline();
    const [latency] = scenario.latencies;
    if (!latency)
      throw new Error("fixture bug: expected at least one latency metric");
    latency.p95Ms = 70;
    latency.p95LimitMs = 50;
    latency.passed = true;
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects a scenario marked PASSED while one of its own metrics reports passed:false", () => {
    const { baseline, scenario } = validBaseline();
    scenario.signals.push({
      metricId: "unexpected-5xx",
      displayName: "Unexpected server errors",
      unit: "count",
      value: 3,
      passed: false,
    });
    // status stays "PASSED" - deliberately inconsistent with the failed signal above.
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects a scenario marked REGRESSION when none of its own metrics reports passed:false", () => {
    const { baseline, scenario } = validBaseline();
    scenario.status = "REGRESSION";
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects a non-integer count signal value", () => {
    const { baseline, scenario } = validBaseline();
    const [signal] = scenario.signals;
    if (!signal)
      throw new Error("fixture bug: expected at least one signal metric");
    signal.value = 1.5;
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects a rate signal value outside [0, 1]", () => {
    const { baseline, scenario } = validBaseline();
    scenario.signals.push({
      metricId: "unexpected-error-rate",
      displayName: "Unexpected error rate",
      unit: "rate",
      value: 1.2,
      passed: true,
    });
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects a scenario with zero latency and zero signal metrics", () => {
    const { baseline, scenario } = validBaseline();
    scenario.latencies = [];
    scenario.signals = [];
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects a duplicate scenarioId", () => {
    const { baseline, scenario } = validBaseline();
    baseline.scenarios.push(structuredClone(scenario));
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects a duplicate metricId within one scenario", () => {
    const { baseline, scenario } = validBaseline();
    scenario.signals.push({
      metricId: "liveness",
      displayName: "Duplicate of a latency metricId",
      unit: "count",
      value: 0,
      passed: true,
    });
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects an empty scenarios array", () => {
    const { baseline } = validBaseline();
    baseline.scenarios = [];
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });

  it("rejects schemaVersion values other than the literal 1", () => {
    const { baseline } = validBaseline();
    const broken: Record<string, unknown> = { ...baseline, schemaVersion: 2 };
    expect(PerformanceBaseline.safeParse(broken).success).toBe(false);
  });

  it("rejects generatedAt predating source.measuredAt", () => {
    const { baseline } = validBaseline();
    baseline.source.measuredAt = "2026-06-01T12:00:00.000Z";
    baseline.generatedAt = "2026-01-01T00:00:00.000Z";
    expect(PerformanceBaseline.safeParse(baseline).success).toBe(false);
  });
});
