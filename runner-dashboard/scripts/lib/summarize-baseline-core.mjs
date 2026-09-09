// D4.4.3a - the pure, filesystem-free half of the baseline summarizer: turns a raw-k6-JSON-per-
// scenario map plus a metadata sidecar into the committed `baseline.json` shape, or throws.
// `summarize-baseline.mjs` (the CLI wrapper) owns the real, project-specific scenario config table
// and all file I/O; this module knows nothing about paths - it exists so the golden-output test can
// exercise the actual summarization/validation logic directly, with small fixture objects, instead
// of shelling out to the CLI or mocking the filesystem.
//
// Fail-closed by design (mirrors this project's own retention/recovery services): a baseline.json
// is a durable, audited artifact - it must never silently embed a value that doesn't mean what it
// claims to, so every one of the checks below throws a `SummarizeBaselineError` rather than
// producing a partial or misleading result.
//
// `generatedAt` is deliberately NOT part of the metadata sidecar, unlike every other provenance
// field: it means "when was this baseline.json actually built," which is only ever true at real
// generation time - moving it into metadata.json would make it a lie the moment the sidecar is
// reused for a drift check. `check-baseline.mjs` instead re-injects the *committed* file's own
// `generatedAt` as the `now` override when regenerating for comparison, so a real content drift is
// never masked by - nor falsely flagged because of - a timestamp that naturally differs on every
// real `summarize-baseline.mjs` invocation. That is the one field this module's own output is
// allowed to vary on run-to-run without indicating drift.

export class SummarizeBaselineError extends Error {
  constructor(message) {
    super(message);
    this.name = "SummarizeBaselineError";
  }
}

const P50_KEY = "p(50)";
const P95_KEY = "p(95)";
const P99_KEY = "p(99)";

// Matches a k6 Trend threshold key of the exact shape every locked latency gate in this project
// uses, e.g. "p(95)<250" - see performance/k6/*.js's own D4.4.2 threshold comments.
const P95_THRESHOLD_KEY_PATTERN = /^p\(95\)<(\d+(?:\.\d+)?)$/;

const VALID_PROFILES = new Set(["production-policy", "throughput"]);
const VALID_EXECUTION_MODES = new Set(["cold", "warm"]);
const COMMIT_SHA_PATTERN = /^[0-9a-f]{40}$/;
const ISO_UTC_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/;
const K6_VERSION_PATTERN = /^\d+\.\d+\.\d+$/;
const WORKFLOW_URL_PATTERN = /^https:\/\/\S+\/actions\/runs\/(\d+)$/;

/**
 * @param {object} args
 * @param {object} args.metadata - the metadata.json sidecar: measuredAt, commitSha, profile,
 *   executionMode, repetitions, runnerImage, k6Version, workflowRun ({id, attempt, url}). Every
 *   field is validated - see `validateMetadata` - never merely copied through.
 * @param {Record<string, object>} args.rawByScenario - scenarioId -> that scenario's raw k6
 *   summary JSON (`{ scenario, metrics }`, exactly what `performance/k6/lib/summary.js` writes).
 * @param {Array<object>} args.scenarioConfigs - the expected scenario set, in output order. Each
 *   entry: { id, displayName,
 *   latencyMetrics: [{ metricId, displayName, rawMetricKey, thresholdRequired? }],
 *   signalMetrics: [{ metricId, displayName, kind: 'counter'|'rate', rawMetricKey,
 *   thresholdRequired? }] }. `thresholdRequired` defaults to `true` - a metric this project
 *   genuinely never gates on (create-run's own single-sample latency, sse-replay's informational
 *   event count, the two scenarios' own uncapped `expected_429` counters) must set it to `false`
 *   explicitly, never by omitting a threshold and hoping nobody notices.
 * @param {() => string} [args.now] - injected clock for `generatedAt`, defaults to
 *   `new Date().toISOString()` - overridable so the golden-output test (and `check-baseline.mjs`'s
 *   own drift check) can pin an exact value.
 */
export function summarizeBaseline({
  metadata,
  rawByScenario,
  scenarioConfigs,
  now,
}) {
  validateMetadata(metadata);
  assertNoDuplicateScenarioIds(scenarioConfigs);
  assertExactScenarioSet(scenarioConfigs, rawByScenario);

  const scenarios = scenarioConfigs.map((config) =>
    summarizeScenario(config, rawByScenario[config.id]),
  );

  const generatedAt = (now ?? (() => new Date().toISOString()))();
  requireIsoUtc("(generatedAt)", "generatedAt", generatedAt);

  return {
    schemaVersion: 1,
    generatedAt,
    source: {
      measuredAt: metadata.measuredAt,
      commitSha: metadata.commitSha,
      profile: metadata.profile,
      executionMode: metadata.executionMode,
      repetitions: metadata.repetitions,
      runnerImage: metadata.runnerImage,
      k6Version: metadata.k6Version,
      workflowRun: metadata.workflowRun,
    },
    scenarios,
  };
}

function fail(message) {
  throw new SummarizeBaselineError(message);
}

function requireIsoUtc(scenarioLabel, fieldLabel, value) {
  if (
    typeof value !== "string" ||
    !ISO_UTC_PATTERN.test(value) ||
    Number.isNaN(Date.parse(value))
  ) {
    fail(
      `${scenarioLabel}: ${fieldLabel} must be an ISO-8601 UTC timestamp ending in "Z" (got ${JSON.stringify(value)}).`,
    );
  }
}

function requirePositiveInteger(fieldLabel, value) {
  if (typeof value !== "number" || !Number.isInteger(value) || value <= 0) {
    fail(
      `metadata.json: ${fieldLabel} must be a positive integer (got ${JSON.stringify(value)}).`,
    );
  }
}

function requireNonEmptyString(fieldLabel, value) {
  if (typeof value !== "string" || value.trim().length === 0) {
    fail(
      `metadata.json: ${fieldLabel} must be a non-empty string (got ${JSON.stringify(value)}).`,
    );
  }
}

/**
 * Every provenance field a committed baseline.json claims must be real, not merely present -
 * `JSON.stringify` silently drops an `undefined` value, so an unvalidated missing field doesn't
 * even show up as `null` in the output; it just vanishes.
 */
function validateMetadata(metadata) {
  if (!metadata || typeof metadata !== "object") {
    fail("metadata.json: expected a JSON object.");
  }
  requireIsoUtc("metadata.json", "measuredAt", metadata.measuredAt);
  if (
    typeof metadata.commitSha !== "string" ||
    !COMMIT_SHA_PATTERN.test(metadata.commitSha)
  ) {
    fail(
      `metadata.json: commitSha must be exactly 40 lowercase hex characters (got ${JSON.stringify(metadata.commitSha)}).`,
    );
  }
  if (!VALID_PROFILES.has(metadata.profile)) {
    fail(
      `metadata.json: profile must be one of ${[...VALID_PROFILES].join(", ")} (got ${JSON.stringify(metadata.profile)}).`,
    );
  }
  if (!VALID_EXECUTION_MODES.has(metadata.executionMode)) {
    fail(
      `metadata.json: executionMode must be one of ${[...VALID_EXECUTION_MODES].join(", ")} (got ${JSON.stringify(metadata.executionMode)}).`,
    );
  }
  requirePositiveInteger("repetitions", metadata.repetitions);
  requireNonEmptyString("runnerImage", metadata.runnerImage);
  if (
    typeof metadata.k6Version !== "string" ||
    !K6_VERSION_PATTERN.test(metadata.k6Version)
  ) {
    fail(
      `metadata.json: k6Version must be a plain semver string like "1.5.0" (got ${JSON.stringify(metadata.k6Version)}).`,
    );
  }
  if (!metadata.workflowRun || typeof metadata.workflowRun !== "object") {
    fail("metadata.json: workflowRun must be an object ({id, attempt, url}).");
  }
  requirePositiveInteger("workflowRun.id", metadata.workflowRun.id);
  requirePositiveInteger("workflowRun.attempt", metadata.workflowRun.attempt);
  const urlMatch =
    typeof metadata.workflowRun.url === "string"
      ? WORKFLOW_URL_PATTERN.exec(metadata.workflowRun.url)
      : null;
  if (!urlMatch) {
    fail(
      `metadata.json: workflowRun.url must be an https URL ending in "/actions/runs/<id>" (got ${JSON.stringify(metadata.workflowRun.url)}).`,
    );
  }
  if (Number(urlMatch[1]) !== metadata.workflowRun.id) {
    fail(
      `metadata.json: workflowRun.url's own run id (${urlMatch[1]}) does not match workflowRun.id ` +
        `(${metadata.workflowRun.id}).`,
    );
  }
}

function assertNoDuplicateScenarioIds(scenarioConfigs) {
  const seen = new Set();
  for (const config of scenarioConfigs) {
    if (seen.has(config.id)) {
      fail(`Duplicate scenarioId in scenarioConfigs: "${config.id}"`);
    }
    seen.add(config.id);
  }
}

function assertExactScenarioSet(scenarioConfigs, rawByScenario) {
  const expected = new Set(scenarioConfigs.map((c) => c.id));
  const actual = new Set(Object.keys(rawByScenario));

  const missing = [...expected].filter((id) => !actual.has(id));
  const extra = [...actual].filter((id) => !expected.has(id));

  if (missing.length > 0 || extra.length > 0) {
    const parts = [];
    if (missing.length > 0) parts.push(`missing: ${missing.join(", ")}`);
    if (extra.length > 0) parts.push(`unexpected extra: ${extra.join(", ")}`);
    fail(
      `Raw scenario results do not match the expected scenario set (${parts.join("; ")}).`,
    );
  }
}

function summarizeScenario(config, raw) {
  if (raw.scenario !== config.id) {
    fail(
      `Scenario id/content mismatch: expected raw JSON for "${config.id}" to have ` +
        `"scenario": "${config.id}", found "${raw.scenario}".`,
    );
  }

  // Closes the gap a purely allowlist-based check would leave: a metric this scenario's config
  // never selected into latencies/signals (a k6 built-in, or one this session simply forgot to
  // map) can still carry a real, failing threshold - and would otherwise never be looked at at
  // all. A baseline may only be generated from a run where *nothing* in its raw output failed,
  // not just the subset this file happens to surface.
  assertNoFailingThresholdAnywhere(config.id, raw);

  const metricIdsSeen = new Set();
  const latencies = config.latencyMetrics.map((metricConfig) =>
    summarizeLatencyMetric(config.id, raw, metricConfig, metricIdsSeen),
  );
  const signals = config.signalMetrics.map((metricConfig) =>
    summarizeSignalMetric(config.id, raw, metricConfig, metricIdsSeen),
  );

  return {
    scenarioId: config.id,
    displayName: config.displayName,
    status: "PASSED",
    latencies,
    signals,
  };
}

function assertNoFailingThresholdAnywhere(scenarioId, raw) {
  for (const [rawMetricKey, metric] of Object.entries(raw.metrics)) {
    requireEveryThresholdOk(scenarioId, rawMetricKey, metric.thresholds);
  }
}

function requireRawMetric(scenarioId, raw, rawMetricKey) {
  const metric = raw.metrics[rawMetricKey];
  if (!metric) {
    fail(
      `Scenario "${scenarioId}": expected metric "${rawMetricKey}" was not present in its raw k6 summary.`,
    );
  }
  return metric;
}

function requireMetricIdUnique(scenarioId, metricIdsSeen, metricId) {
  if (metricIdsSeen.has(metricId)) {
    fail(`Scenario "${scenarioId}": duplicate metricId "${metricId}".`);
  }
  metricIdsSeen.add(metricId);
}

function requireFiniteNonNegative(scenarioId, metricId, label, value) {
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    fail(
      `Scenario "${scenarioId}", metric "${metricId}": ${label} is not a finite, non-negative number (got ${value}).`,
    );
  }
}

/** Every threshold this project's k6 scripts define resolves to a single {ok: boolean} entry. */
function requireEveryThresholdOk(scenarioId, rawMetricKey, thresholds) {
  if (!thresholds) {
    return;
  }
  for (const [thresholdExpression, result] of Object.entries(thresholds)) {
    if (result.ok !== true) {
      fail(
        `Scenario "${scenarioId}", raw metric "${rawMetricKey}": threshold "${thresholdExpression}" ` +
          `is not ok:true (${JSON.stringify(result)}). A baseline can only be generated from a run ` +
          "where every threshold genuinely passed.",
      );
    }
  }
}

function requireThresholdPresentIfRequired(
  scenarioId,
  metricId,
  rawMetricKey,
  thresholds,
  thresholdRequired,
) {
  if (
    thresholdRequired &&
    (!thresholds || Object.keys(thresholds).length === 0)
  ) {
    fail(
      `Scenario "${scenarioId}", metric "${metricId}" (raw "${rawMetricKey}"): this metric is ` +
        "configured as thresholdRequired but the raw k6 summary carries no threshold at all - " +
        "either the k6 script's own options.thresholds regressed, or this metric config should " +
        "explicitly set thresholdRequired: false.",
    );
  }
}

function extractP95Limit(thresholds) {
  if (!thresholds) {
    return null;
  }
  for (const thresholdExpression of Object.keys(thresholds)) {
    const match = P95_THRESHOLD_KEY_PATTERN.exec(thresholdExpression);
    if (match) {
      return Number(match[1]);
    }
  }
  return null;
}

function summarizeLatencyMetric(scenarioId, raw, metricConfig, metricIdsSeen) {
  const {
    metricId,
    displayName,
    rawMetricKey,
    thresholdRequired = true,
  } = metricConfig;
  requireMetricIdUnique(scenarioId, metricIdsSeen, metricId);
  const metric = requireRawMetric(scenarioId, raw, rawMetricKey);
  const values = metric.values;
  requireThresholdPresentIfRequired(
    scenarioId,
    metricId,
    rawMetricKey,
    metric.thresholds,
    thresholdRequired,
  );

  const sampleCount = values.count;
  requireFiniteNonNegative(scenarioId, metricId, "sampleCount", sampleCount);
  if (!Number.isInteger(sampleCount)) {
    fail(
      `Scenario "${scenarioId}", metric "${metricId}": sampleCount must be an integer (got ${sampleCount}).`,
    );
  }
  if (sampleCount === 0) {
    fail(
      `Scenario "${scenarioId}", metric "${metricId}": sampleCount is 0 - a baseline cannot be generated from a metric with zero recorded samples.`,
    );
  }

  const p50Ms = values[P50_KEY];
  const p95Ms = values[P95_KEY];
  const p99Ms = values[P99_KEY];
  requireFiniteNonNegative(scenarioId, metricId, "p50Ms", p50Ms);
  requireFiniteNonNegative(scenarioId, metricId, "p95Ms", p95Ms);
  requireFiniteNonNegative(scenarioId, metricId, "p99Ms", p99Ms);
  if (p50Ms > p95Ms || p95Ms > p99Ms) {
    fail(
      `Scenario "${scenarioId}", metric "${metricId}": percentile ordering violated ` +
        `(p50=${p50Ms}, p95=${p95Ms}, p99=${p99Ms} - expected p50<=p95<=p99).`,
    );
  }

  const p95LimitMs = extractP95Limit(metric.thresholds);

  return {
    metricId,
    displayName,
    sampleCount,
    p50Ms,
    p95Ms,
    p99Ms,
    p95LimitMs,
    passed: metric.thresholds ? true : null,
  };
}

function summarizeSignalMetric(scenarioId, raw, metricConfig, metricIdsSeen) {
  const {
    metricId,
    displayName,
    kind,
    rawMetricKey,
    thresholdRequired = true,
  } = metricConfig;
  requireMetricIdUnique(scenarioId, metricIdsSeen, metricId);
  const metric = requireRawMetric(scenarioId, raw, rawMetricKey);
  requireThresholdPresentIfRequired(
    scenarioId,
    metricId,
    rawMetricKey,
    metric.thresholds,
    thresholdRequired,
  );

  const value = kind === "counter" ? metric.values.count : metric.values.rate;
  requireFiniteNonNegative(scenarioId, metricId, "value", value);
  if (kind === "counter" && !Number.isInteger(value)) {
    fail(
      `Scenario "${scenarioId}", metric "${metricId}": counter value must be an integer (got ${value}).`,
    );
  }
  if (kind === "rate" && value > 1) {
    fail(
      `Scenario "${scenarioId}", metric "${metricId}": rate value must be within [0, 1] (got ${value}).`,
    );
  }

  return {
    metricId,
    displayName,
    unit: kind === "counter" ? "count" : "rate",
    value,
    passed: metric.thresholds ? true : null,
  };
}
