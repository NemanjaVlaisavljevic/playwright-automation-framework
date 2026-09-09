// D4.4.1b - a shared handleSummary() across every scenario script (a review-locked decision): the
// exact JSON shape and the k6 version producing it must be locked together - k6 1.5 introduced a
// new machine-readable summary format that becomes the default in k6 2, so every scenario script
// must produce the same shape via this one function, never each inventing its own slightly
// different one. Deliberately no remote `jslib.k6.io` import (a fancier colored text summary is
// not worth trading away offline/reproducible CI for) - only `JSON.stringify` on k6's own already-
// computed `data.metrics`, which already reflects each script's own `summaryTrendStats` choice.
//
// D4.4.3a - a real gap found only by trying to actually consume every scenario's raw output for the
// baseline summarizer: `sse-replay.js`/`sse-connection-cap.js`/`create-run.js` never set
// `summaryTrendStats` at all, so k6's own default (`avg,min,med,max,p(90),p(95)` - no `count`, no
// `p(50)`, no `p(99)`) silently diverged from `public-read.js`/`artifact-reads.js`/`health.js`'s own
// explicit choice, even though this file's own header already claimed the whole point of one shared
// `handleSummary()` was a single locked output shape. `SUMMARY_TREND_STATS` is now the one place
// that shape is declared - every scenario script's own `options.summaryTrendStats` must reference
// this constant, never repeat or diverge from it.
export const SUMMARY_TREND_STATS = ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)', 'count'];
//
// A review finding: tagging a Trend sample does not by itself make k6 track a separate
// `success_latency_ms{endpoint:...}` sub-metric - only referencing that exact tag combination in
// `options.thresholds` does. `expectedEndpoints` is this scenario's own declared list of endpoints
// it means to cover; if any of them never actually produced a tracked sub-metric (a missing
// threshold entry, or genuinely zero successful responses for it), this throws rather than
// silently publishing an incomplete summary - the same self-checking-assertion pattern already
// used in performance/seed/seed.sql's own runtime acceptance check.
export function handleSummary(scenarioName, expectedEndpoints, data) {
  // Checking key presence alone is not enough - a tag combination referenced in a threshold gets
  // its own metric key even with zero real samples ever added to it, which would let this check
  // pass while an endpoint silently never recorded a single genuine success.
  const missing = expectedEndpoints.filter((endpoint) => {
    const metric = data.metrics[`success_latency_ms{endpoint:${endpoint}}`];
    return metric === undefined || !(metric.values.count > 0);
  });
  if (missing.length > 0) {
    throw new Error(
      `performance k6 (${scenarioName}): summary has no recorded successful responses for: ` +
        `${missing.join(', ')}. Every endpoint this scenario covers must be referenced in ` +
        'options.thresholds as success_latency_ms{endpoint:<name>} AND have actually recorded at ' +
        'least one genuine success during this run.'
    );
  }
  const json = JSON.stringify({ scenario: scenarioName, metrics: data.metrics }, null, 2);
  return {
    stdout: json,
    [`/results/${scenarioName}.json`]: json,
  };
}
