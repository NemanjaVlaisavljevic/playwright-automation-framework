import { z } from "zod";

/**
 * Frontend contract for the committed, manually-published `public/performance/baseline.json`
 * (hand-written, no generated OpenAPI counterpart). Also checks cross-field invariants (e.g.
 * `passed` matching `p95Ms < p95LimitMs`); raw k6 checks are enforced separately at generation time.
 */

const nonBlankString = z.string().regex(/\S/, "must not be blank");

const Profile = z.enum(["production-policy", "throughput"]);
export type Profile = z.infer<typeof Profile>;

const ExecutionMode = z.enum(["cold", "warm"]);
export type ExecutionMode = z.infer<typeof ExecutionMode>;

const ScenarioStatus = z.enum(["PASSED", "REGRESSION"]);
export type ScenarioStatus = z.infer<typeof ScenarioStatus>;

/** Matches `git rev-parse HEAD`'s own output shape - never a shortened/abbreviated SHA. */
const CommitSha = z
  .string()
  .regex(/^[0-9a-f]{40}$/, "must be exactly 40 lowercase hex characters");

/** A plain semver string, e.g. "1.5.0" - the pinned `grafana/k6` image tag, never a `^`/`~` range. */
const K6Version = z
  .string()
  .regex(/^\d+\.\d+\.\d+$/, 'must be a plain semver string like "1.5.0"');

const GITHUB_ACTIONS_RUN_URL_PATH = /^\/[^/]+\/[^/]+\/actions\/runs\/(\d+)$/;

/**
 * Parses `value` as a plain `https://github.com/<owner>/<repo>/actions/runs/<id>` URL and returns
 * the embedded run id, or `null`. Checks exact host/protocol/shape, not just a matching suffix, so
 * e.g. `https://evil.example/actions/runs/123` is rejected.
 */
function parseGithubActionsRunUrl(value: string): number | null {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return null;
  }
  if (url.protocol !== "https:") return null;
  if (url.hostname !== "github.com") return null;
  if (url.username !== "" || url.password !== "") return null;
  if (url.search !== "" || url.hash !== "") return null;
  const match = GITHUB_ACTIONS_RUN_URL_PATH.exec(url.pathname);
  if (!match) return null;
  return Number(match[1]);
}

const WorkflowRun = z
  .object({
    id: z.int().positive(),
    attempt: z.int().positive(),
    url: nonBlankString,
  })
  .strict()
  .superRefine((workflowRun, ctx) => {
    const embeddedRunId = parseGithubActionsRunUrl(workflowRun.url);
    if (embeddedRunId === null) {
      ctx.addIssue({
        code: "custom",
        message:
          "url must be a plain https://github.com/<owner>/<repo>/actions/runs/<id> URL - no credentials, query string, or hash",
        path: ["url"],
      });
      return;
    }
    if (embeddedRunId !== workflowRun.id) {
      ctx.addIssue({
        code: "custom",
        message: `url's own embedded run id (${embeddedRunId}) does not match workflowRun.id (${workflowRun.id})`,
        path: ["url"],
      });
    }
  });
export type WorkflowRun = z.infer<typeof WorkflowRun>;

const Source = z
  .object({
    measuredAt: z.iso.datetime(),
    commitSha: CommitSha,
    profile: Profile,
    executionMode: ExecutionMode,
    repetitions: z.int().positive(),
    runnerImage: nonBlankString,
    k6Version: K6Version,
    workflowRun: WorkflowRun,
  })
  .strict();
export type Source = z.infer<typeof Source>;

const LatencyMetric = z
  .object({
    metricId: nonBlankString,
    displayName: nonBlankString,
    sampleCount: z.int().positive(),
    p50Ms: z.number().nonnegative(),
    p95Ms: z.number().nonnegative(),
    p99Ms: z.number().nonnegative(),
    // null when the metric has no configured threshold (`thresholdRequired: false`), never omitted.
    p95LimitMs: z.number().positive().nullable(),
    passed: z.boolean().nullable(),
  })
  .strict()
  .superRefine((metric, ctx) => {
    if (metric.p50Ms > metric.p95Ms || metric.p95Ms > metric.p99Ms) {
      ctx.addIssue({
        code: "custom",
        message: `percentile ordering violated (p50=${metric.p50Ms}, p95=${metric.p95Ms}, p99=${metric.p99Ms} - expected p50<=p95<=p99)`,
        path: [],
      });
    }

    const isGated = metric.p95LimitMs !== null;
    const hasPassedVerdict = metric.passed !== null;
    if (isGated !== hasPassedVerdict) {
      ctx.addIssue({
        code: "custom",
        message: `p95LimitMs and passed must be null together or set together (got p95LimitMs=${metric.p95LimitMs}, passed=${metric.passed})`,
        path: [],
      });
      return;
    }
    if (isGated) {
      // k6's "p(95)<X" gate semantics: strictly less-than, never less-than-or-equal.
      const expectedPassed = metric.p95Ms < (metric.p95LimitMs as number);
      if (metric.passed !== expectedPassed) {
        ctx.addIssue({
          code: "custom",
          message: `passed (${metric.passed}) does not match the real p95Ms<p95LimitMs comparison (${metric.p95Ms}<${metric.p95LimitMs} = ${expectedPassed})`,
          path: ["passed"],
        });
      }
    }
  });
export type LatencyMetric = z.infer<typeof LatencyMetric>;

const CountSignalMetric = z
  .object({
    metricId: nonBlankString,
    displayName: nonBlankString,
    unit: z.literal("count"),
    value: z.int().nonnegative(),
    passed: z.boolean().nullable(),
  })
  .strict();

const RateSignalMetric = z
  .object({
    metricId: nonBlankString,
    displayName: nonBlankString,
    unit: z.literal("rate"),
    value: z.number().min(0).max(1),
    passed: z.boolean().nullable(),
  })
  .strict();

/** A count is always a non-negative integer sample; a rate is always a real 0..1 fraction. */
const SignalMetric = z.discriminatedUnion("unit", [
  CountSignalMetric,
  RateSignalMetric,
]);
export type SignalMetric = z.infer<typeof SignalMetric>;

const Scenario = z
  .object({
    scenarioId: nonBlankString,
    displayName: nonBlankString,
    status: ScenarioStatus,
    latencies: z.array(LatencyMetric),
    signals: z.array(SignalMetric),
  })
  .strict()
  .superRefine((scenario, ctx) => {
    if (scenario.latencies.length + scenario.signals.length === 0) {
      ctx.addIssue({
        code: "custom",
        message: "a scenario must report at least one latency or signal metric",
        path: [],
      });
    }

    const allMetrics = [...scenario.latencies, ...scenario.signals];
    const seenMetricIds = new Set<string>();
    let anyFailed = false;
    for (const [index, metric] of allMetrics.entries()) {
      if (seenMetricIds.has(metric.metricId)) {
        ctx.addIssue({
          code: "custom",
          message: `duplicate metricId "${metric.metricId}" within scenario "${scenario.scenarioId}"`,
          path:
            index < scenario.latencies.length
              ? ["latencies", index]
              : ["signals", index - scenario.latencies.length],
        });
      }
      seenMetricIds.add(metric.metricId);
      if (metric.passed === false) {
        anyFailed = true;
      }
    }

    const expectedStatus: z.infer<typeof ScenarioStatus> = anyFailed
      ? "REGRESSION"
      : "PASSED";
    if (scenario.status !== expectedStatus) {
      ctx.addIssue({
        code: "custom",
        message: `status "${scenario.status}" does not match the status derivable from its own metrics' passed values ("${expectedStatus}")`,
        path: ["status"],
      });
    }
  });
export type Scenario = z.infer<typeof Scenario>;

export const PerformanceBaseline = z
  .object({
    schemaVersion: z.literal(1),
    generatedAt: z.iso.datetime(),
    source: Source,
    scenarios: z.array(Scenario).min(1),
  })
  .strict()
  .superRefine((baseline, ctx) => {
    const seenScenarioIds = new Set<string>();
    for (const [index, scenario] of baseline.scenarios.entries()) {
      if (seenScenarioIds.has(scenario.scenarioId)) {
        ctx.addIssue({
          code: "custom",
          message: `duplicate scenarioId "${scenario.scenarioId}"`,
          path: ["scenarios", index],
        });
      }
      seenScenarioIds.add(scenario.scenarioId);
    }

    if (
      Date.parse(baseline.generatedAt) < Date.parse(baseline.source.measuredAt)
    ) {
      ctx.addIssue({
        code: "custom",
        message: `generatedAt (${baseline.generatedAt}) predates source.measuredAt (${baseline.source.measuredAt}) - a baseline cannot claim to be generated before its own measurement finished`,
        path: ["generatedAt"],
      });
    }
  });
export type PerformanceBaseline = z.infer<typeof PerformanceBaseline>;
