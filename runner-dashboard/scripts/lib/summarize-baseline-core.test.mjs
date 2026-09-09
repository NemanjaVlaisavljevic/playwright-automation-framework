// D4.4.3a - the golden-output test for the baseline summarizer, plus one focused test per
// fail-closed validation the design review called for. The golden fixture is deliberately a single
// scenario (health.js's real shape, trimmed to round numbers) - proves the summarizer is
// deterministic and correctly implemented, independent of whatever the real, six-scenario
// `baseline.json` currently contains (see summarize-baseline.mjs's own SCENARIO_CONFIGS for that).
import { readFile } from "node:fs/promises";
import path from "node:path";
import { describe, expect, it } from "vitest";
import {
  summarizeBaseline,
  SummarizeBaselineError,
} from "./summarize-baseline-core.mjs";

const FIXTURES_DIR = path.resolve(
  import.meta.dirname,
  "__fixtures__/summarize-baseline",
);

const FIXED_NOW = () => "2026-06-01T12:00:00.000Z";

const HEALTH_SCENARIO_CONFIG = {
  id: "health",
  displayName: "Health checks",
  latencyMetrics: [
    {
      metricId: "liveness",
      displayName: "Liveness",
      rawMetricKey: "success_latency_ms{endpoint:liveness}",
    },
    {
      metricId: "readiness",
      displayName: "Readiness",
      rawMetricKey: "success_latency_ms{endpoint:readiness}",
    },
  ],
  signalMetrics: [
    {
      metricId: "unexpected-429",
      displayName: "Unexpected throttled responses",
      kind: "counter",
      rawMetricKey: "unexpected_429",
    },
    {
      metricId: "unexpected-5xx",
      displayName: "Unexpected server errors",
      kind: "counter",
      rawMetricKey: "unexpected_5xx",
    },
    {
      metricId: "unexpected-error-rate",
      displayName: "Unexpected error rate",
      kind: "rate",
      rawMetricKey: "unexpected_error_rate",
    },
  ],
};

async function readFixtureJson(...segments) {
  return JSON.parse(
    await readFile(path.join(FIXTURES_DIR, ...segments), "utf8"),
  );
}

describe("summarizeBaseline - golden output", () => {
  it("matches the committed golden baseline field-for-field for the health fixture", async () => {
    const metadata = await readFixtureJson("metadata.json");
    const healthRaw = await readFixtureJson("raw", "health.json");
    const expected = await readFixtureJson("expected-baseline.json");

    const actual = summarizeBaseline({
      metadata,
      rawByScenario: { health: healthRaw },
      scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
      now: FIXED_NOW,
    });

    expect(actual).toStrictEqual(expected);
  });
});

describe("summarizeBaseline - fail-closed validation", () => {
  // A complete, valid metadata object - every individual field-validation test below mutates one
  // field off of this rather than constructing its own from scratch.
  function validMetadata(overrides = {}) {
    return {
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
      ...overrides,
    };
  }

  function healthRaw(overrides = {}) {
    return {
      scenario: "health",
      metrics: {
        "success_latency_ms{endpoint:liveness}": {
          thresholds: { "p(95)<50": { ok: true } },
          values: { count: 30, "p(50)": 3.5, "p(95)": 5.0, "p(99)": 6.0 },
        },
        "success_latency_ms{endpoint:readiness}": {
          thresholds: { "p(95)<50": { ok: true } },
          values: { count: 30, "p(50)": 3.4, "p(95)": 4.8, "p(99)": 5.9 },
        },
        unexpected_429: {
          thresholds: { "count==0": { ok: true } },
          values: { count: 0 },
        },
        unexpected_5xx: {
          thresholds: { "count==0": { ok: true } },
          values: { count: 0 },
        },
        unexpected_error_rate: {
          thresholds: { "rate==0": { ok: true } },
          values: { rate: 0 },
        },
        ...overrides,
      },
    };
  }

  it("throws when a scenario is missing", () => {
    expect(() =>
      summarizeBaseline({
        metadata: validMetadata(),
        rawByScenario: {},
        scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
      }),
    ).toThrow(SummarizeBaselineError);
  });

  it("throws when an extra, unexpected scenario is present", () => {
    expect(() =>
      summarizeBaseline({
        metadata: validMetadata(),
        rawByScenario: { health: healthRaw(), "extra-scenario": healthRaw() },
        scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
      }),
    ).toThrow(/unexpected extra/);
  });

  it("throws on a duplicate scenarioId across scenarioConfigs", () => {
    expect(() =>
      summarizeBaseline({
        metadata: validMetadata(),
        rawByScenario: { health: healthRaw() },
        scenarioConfigs: [HEALTH_SCENARIO_CONFIG, HEALTH_SCENARIO_CONFIG],
      }),
    ).toThrow(/Duplicate scenarioId/);
  });

  it("throws when the raw JSON's own scenario field does not match the expected id", () => {
    const mismatched = healthRaw();
    mismatched.scenario = "not-health";
    expect(() =>
      summarizeBaseline({
        metadata: validMetadata(),
        rawByScenario: { health: mismatched },
        scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
      }),
    ).toThrow(/mismatch/);
  });

  it("throws when a selected metric's own threshold is not ok:true", () => {
    const broken = healthRaw();
    broken.metrics["success_latency_ms{endpoint:liveness}"] = {
      thresholds: { "p(95)<50": { ok: false } },
      values: { count: 30, "p(50)": 3.5, "p(95)": 51, "p(99)": 55 },
    };
    expect(() =>
      summarizeBaseline({
        metadata: validMetadata(),
        rawByScenario: { health: broken },
        scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
      }),
    ).toThrow(/not ok:true/);
  });

  it("throws when an UNSELECTED raw metric's own threshold is not ok:true (full-scan check)", () => {
    const broken = healthRaw();
    // A metric no scenario config selects at all - simulates a k6 built-in, or a metric this file
    // simply never mapped, quietly carrying a real failing threshold.
    broken.metrics.some_unmapped_metric = {
      thresholds: { "count==0": { ok: false } },
      values: { count: 3 },
    };
    expect(() =>
      summarizeBaseline({
        metadata: validMetadata(),
        rawByScenario: { health: broken },
        scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
      }),
    ).toThrow(/not ok:true/);
  });

  it("throws when a required metric carries no threshold at all", () => {
    const broken = healthRaw();
    delete broken.metrics["success_latency_ms{endpoint:liveness}"].thresholds;
    expect(() =>
      summarizeBaseline({
        metadata: validMetadata(),
        rawByScenario: { health: broken },
        scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
      }),
    ).toThrow(/thresholdRequired/);
  });

  it("allows a metric explicitly marked thresholdRequired:false to carry no threshold", () => {
    const optionalConfig = {
      ...HEALTH_SCENARIO_CONFIG,
      latencyMetrics: [
        {
          metricId: "liveness",
          displayName: "Liveness",
          rawMetricKey: "success_latency_ms{endpoint:liveness}",
          thresholdRequired: false,
        },
        HEALTH_SCENARIO_CONFIG.latencyMetrics[1],
      ],
    };
    const noThreshold = healthRaw();
    delete noThreshold.metrics["success_latency_ms{endpoint:liveness}"]
      .thresholds;

    const result = summarizeBaseline({
      metadata: validMetadata(),
      rawByScenario: { health: noThreshold },
      scenarioConfigs: [optionalConfig],
      now: FIXED_NOW,
    });

    const liveness = result.scenarios[0].latencies.find(
      (m) => m.metricId === "liveness",
    );
    expect(liveness.p95LimitMs).toBeNull();
    expect(liveness.passed).toBeNull();
    expect(result.scenarios[0].status).toBe("PASSED");
  });

  it("throws when sampleCount is zero", () => {
    const broken = healthRaw();
    broken.metrics["success_latency_ms{endpoint:liveness}"].values.count = 0;
    expect(() =>
      summarizeBaseline({
        metadata: validMetadata(),
        rawByScenario: { health: broken },
        scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
      }),
    ).toThrow(/sampleCount is 0/);
  });

  it("throws when sampleCount is not an integer", () => {
    const broken = healthRaw();
    broken.metrics["success_latency_ms{endpoint:liveness}"].values.count = 29.5;
    expect(() =>
      summarizeBaseline({
        metadata: validMetadata(),
        rawByScenario: { health: broken },
        scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
      }),
    ).toThrow(/sampleCount must be an integer/);
  });

  it("throws on a NaN latency value", () => {
    const broken = healthRaw();
    broken.metrics["success_latency_ms{endpoint:liveness}"].values["p(95)"] =
      NaN;
    expect(() =>
      summarizeBaseline({
        metadata: validMetadata(),
        rawByScenario: { health: broken },
        scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
      }),
    ).toThrow(/finite, non-negative/);
  });

  it("throws on a negative counter signal value", () => {
    const broken = healthRaw();
    broken.metrics.unexpected_429.values.count = -1;
    expect(() =>
      summarizeBaseline({
        metadata: validMetadata(),
        rawByScenario: { health: broken },
        scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
      }),
    ).toThrow(/finite, non-negative/);
  });

  it("throws on a non-integer counter signal value", () => {
    const broken = healthRaw();
    broken.metrics.unexpected_429.values.count = 1.5;
    expect(() =>
      summarizeBaseline({
        metadata: validMetadata(),
        rawByScenario: { health: broken },
        scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
      }),
    ).toThrow(/counter value must be an integer/);
  });

  it("throws when a rate signal value is above 1", () => {
    const broken = healthRaw();
    broken.metrics.unexpected_error_rate.values.rate = 1.2;
    expect(() =>
      summarizeBaseline({
        metadata: validMetadata(),
        rawByScenario: { health: broken },
        scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
      }),
    ).toThrow(/within \[0, 1\]/);
  });

  it("throws when percentile ordering is violated (p95 > p99)", () => {
    const broken = healthRaw();
    broken.metrics["success_latency_ms{endpoint:liveness}"].values["p(95)"] =
      100;
    broken.metrics["success_latency_ms{endpoint:liveness}"].values["p(99)"] =
      6.0;
    expect(() =>
      summarizeBaseline({
        metadata: validMetadata(),
        rawByScenario: { health: broken },
        scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
      }),
    ).toThrow(/ordering violated/);
  });

  it("throws on a duplicate metricId within one scenario's config", () => {
    const duplicateMetricConfig = {
      ...HEALTH_SCENARIO_CONFIG,
      signalMetrics: [
        ...HEALTH_SCENARIO_CONFIG.signalMetrics,
        {
          metricId: "liveness",
          displayName: "Duplicate of a latency metricId",
          kind: "counter",
          rawMetricKey: "unexpected_429",
        },
      ],
    };
    expect(() =>
      summarizeBaseline({
        metadata: validMetadata(),
        rawByScenario: { health: healthRaw() },
        scenarioConfigs: [duplicateMetricConfig],
      }),
    ).toThrow(/duplicate metricId/);
  });

  it("throws when a configured metric is absent from the raw summary", () => {
    const missingMetricConfig = {
      ...HEALTH_SCENARIO_CONFIG,
      signalMetrics: [
        ...HEALTH_SCENARIO_CONFIG.signalMetrics,
        {
          metricId: "does-not-exist",
          displayName: "Missing",
          kind: "counter",
          rawMetricKey: "no_such_metric",
        },
      ],
    };
    expect(() =>
      summarizeBaseline({
        metadata: validMetadata(),
        rawByScenario: { health: healthRaw() },
        scenarioConfigs: [missingMetricConfig],
      }),
    ).toThrow(/was not present/);
  });

  describe("metadata field validation", () => {
    it("throws when a required metadata field is missing", () => {
      const { commitSha: _drop, ...withoutCommitSha } = validMetadata();
      expect(() =>
        summarizeBaseline({
          metadata: withoutCommitSha,
          rawByScenario: { health: healthRaw() },
          scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
        }),
      ).toThrow(/commitSha/);
    });

    it("throws when commitSha is not exactly 40 hex characters", () => {
      expect(() =>
        summarizeBaseline({
          metadata: validMetadata({ commitSha: "deadbeef" }),
          rawByScenario: { health: healthRaw() },
          scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
        }),
      ).toThrow(/commitSha/);
    });

    it("throws on an invalid profile value", () => {
      expect(() =>
        summarizeBaseline({
          metadata: validMetadata({ profile: "staging" }),
          rawByScenario: { health: healthRaw() },
          scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
        }),
      ).toThrow(/profile must be one of/);
    });

    it("throws on a non-UTC measuredAt", () => {
      expect(() =>
        summarizeBaseline({
          metadata: validMetadata({ measuredAt: "2026-01-01T00:00:00+02:00" }),
          rawByScenario: { health: healthRaw() },
          scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
        }),
      ).toThrow(/measuredAt/);
    });

    it("throws when workflowRun.url's run id does not match workflowRun.id", () => {
      expect(() =>
        summarizeBaseline({
          metadata: validMetadata({
            workflowRun: {
              id: 1,
              attempt: 1,
              url: "https://github.com/example/example/actions/runs/2",
            },
          }),
          rawByScenario: { health: healthRaw() },
          scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
        }),
      ).toThrow(/does not match workflowRun.id/);
    });

    it("throws on a non-positive repetitions value", () => {
      expect(() =>
        summarizeBaseline({
          metadata: validMetadata({ repetitions: 0 }),
          rawByScenario: { health: healthRaw() },
          scenarioConfigs: [HEALTH_SCENARIO_CONFIG],
        }),
      ).toThrow(/repetitions/);
    });
  });
});
