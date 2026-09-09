// D4.4.3a - the real, canonical scenario/metric set this project's k6 scripts
// (performance/k6/*.js) produce, shared by summarize-baseline.mjs (generation) and
// check-baseline.mjs (drift check) so the two can never silently disagree about what a valid
// baseline.json is supposed to contain. Kept in one place so a scenario/metric renamed on one side
// and not the other fails loudly (summarize-baseline-core.mjs's own validation) rather than
// silently producing a baseline missing data.
//
// `thresholdRequired: false` marks the small, deliberate set of metrics this project genuinely
// never gates on - everything else defaults to required (summarize-baseline-core.mjs throws if a
// required metric's own raw k6 output carries no threshold at all, catching an accidentally-removed
// locked gate rather than silently reporting `passed: null` for it).
export const SCENARIO_CONFIGS = [
  {
    id: "public-read",
    displayName: "Public read endpoints",
    latencyMetrics: [
      {
        metricId: "capabilities",
        displayName: "Capabilities",
        rawMetricKey: "success_latency_ms{endpoint:capabilities}",
      },
      {
        metricId: "tests",
        displayName: "Test catalog",
        rawMetricKey: "success_latency_ms{endpoint:tests}",
      },
      {
        metricId: "runs-list",
        displayName: "Runs list",
        rawMetricKey: "success_latency_ms{endpoint:runs-list}",
      },
      {
        metricId: "run-detail",
        displayName: "Run detail",
        rawMetricKey: "success_latency_ms{endpoint:run-detail}",
      },
    ],
    signalMetrics: [
      // No threshold gates this - it's a plain observed count of the (expected, harmless) 429s
      // this scenario's own request volume deliberately triggers against the real rate limit.
      {
        metricId: "expected-429",
        displayName: "Expected throttled responses",
        kind: "counter",
        rawMetricKey: "expected_429",
        thresholdRequired: false,
      },
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
  },
  {
    id: "artifact-reads",
    displayName: "Artifact reads",
    latencyMetrics: [
      {
        metricId: "artifacts-list",
        displayName: "Artifacts list",
        rawMetricKey: "success_latency_ms{endpoint:artifacts-list}",
      },
      {
        metricId: "artifact-download",
        displayName: "Artifact download",
        rawMetricKey: "success_latency_ms{endpoint:artifact-download}",
      },
    ],
    signalMetrics: [
      {
        metricId: "expected-429",
        displayName: "Expected throttled responses",
        kind: "counter",
        rawMetricKey: "expected_429",
        thresholdRequired: false,
      },
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
  },
  {
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
      // Never rate-limited (AbuseRateLimitFilter doesn't cover this route) - no expected-429 here.
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
  },
  {
    id: "sse-replay",
    displayName: "SSE replay",
    latencyMetrics: [
      {
        metricId: "connection-establish",
        displayName: "Connection establish",
        rawMetricKey: "sse_connection_establish_ms",
      },
      {
        metricId: "time-to-first-event",
        displayName: "Time to first event",
        rawMetricKey: "sse_time_to_first_event_ms",
      },
      {
        metricId: "replay-duration",
        displayName: "Full replay duration",
        rawMetricKey: "sse_replay_duration_ms",
      },
    ],
    signalMetrics: [
      // Informational only - a raw count of events replayed, never gated on its own.
      {
        metricId: "events-received",
        displayName: "Events replayed",
        kind: "counter",
        rawMetricKey: "sse_events_received",
        thresholdRequired: false,
      },
      {
        metricId: "transport-errors",
        displayName: "Transport errors",
        kind: "counter",
        rawMetricKey: "sse_transport_errors",
      },
      {
        metricId: "replay-correctness",
        displayName: "Replay correctness",
        kind: "rate",
        rawMetricKey: "sse_replay_correctness",
      },
      {
        metricId: "checks",
        displayName: "k6 checks",
        kind: "rate",
        rawMetricKey: "checks",
      },
    ],
  },
  {
    id: "sse-connection-cap",
    displayName: "SSE per-IP connection cap",
    // No latency metric here - http_req_duration is dominated by the scenario's own deliberate
    // 5s hold-open timeout, not a meaningful success latency (see sse-connection-cap.js's own
    // comment). This scenario's real signal is the accepted/rejected split itself.
    latencyMetrics: [],
    signalMetrics: [
      {
        metricId: "accepted-connections",
        displayName: "Accepted connections",
        kind: "counter",
        rawMetricKey: "sse_accepted_connections",
      },
      {
        metricId: "rejected-connections",
        displayName: "Rejected connections",
        kind: "counter",
        rawMetricKey: "sse_rejected_connections",
      },
      {
        metricId: "unexpected-status",
        displayName: "Unexpected status codes",
        kind: "counter",
        rawMetricKey: "sse_unexpected_status",
      },
      {
        metricId: "retry-after-valid",
        displayName: "Valid Retry-After rate",
        kind: "rate",
        rawMetricKey: "sse_retry_after_valid",
      },
      {
        metricId: "checks",
        displayName: "k6 checks",
        kind: "rate",
        rawMetricKey: "checks",
      },
    ],
  },
  {
    id: "create-run",
    displayName: "Create run (admin)",
    latencyMetrics: [
      // No threshold is configured for this metric in create-run.js (single-VU/single-iteration -
      // a p95 of one sample isn't a meaningful gate) - p95LimitMs/passed come back null.
      {
        metricId: "create-run",
        displayName: "Create run (202 Accepted)",
        rawMetricKey: "create_run_success_latency_ms",
        thresholdRequired: false,
      },
    ],
    signalMetrics: [
      {
        metricId: "correctness",
        displayName: "End-to-end correctness",
        kind: "rate",
        rawMetricKey: "create_run_correctness",
      },
      {
        metricId: "unexpected-error-rate",
        displayName: "Unexpected error rate",
        kind: "rate",
        rawMetricKey: "create_run_unexpected_error_rate",
      },
      {
        metricId: "checks",
        displayName: "k6 checks",
        kind: "rate",
        rawMetricKey: "checks",
      },
    ],
  },
];
