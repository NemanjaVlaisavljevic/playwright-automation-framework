import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { getAndClassify } from './lib/http.js';
import { handleSummary as sharedHandleSummary } from './lib/summary.js';
import { resolveProfile, resolveVus, resolveDuration } from './lib/profile.js';

// D4.4.1b - public-read.js: capabilities/tests/runs-list/run-detail, the four endpoints sharing
// the real 120/min `public-read` rate-limit bucket (AbuseRateLimitFilter) - the actual public,
// anonymously-reachable read surface.
//
// D4.4.2 - also runs as a throughput pass (PERFORMANCE_PROFILE=throughput), against
// deploy/docker-compose.performance-throughput.yml's raised public-read-rate-limit - a 429 there
// is itself a bug, never an expected outcome (see rateLimitExpected below). PUBLIC_READ_VUS/
// PUBLIC_READ_DURATION let a throughput run push far more concurrent load than the
// production-policy defaults without touching this file - see performance/README.md's own
// stepped-escalation methodology (10 -> 20 -> 40 VUs, stopping at the first real saturation
// signal, never jumping straight to a single maximal run).
//
// Both 200 and 429 are told to k6 itself as expected outcomes (`http.expectedStatuses`) so its
// own built-in `http_req_failed` correctly reflects only genuinely unexpected statuses - a real
// rate-limited surface's own 429s must never inflate that metric (a review finding: without this,
// k6's own default classification counted every 429 as a failure regardless of the independent
// custom classification below). `lib/metrics.js`'s own `recordOutcome` still separately verifies a
// 429 is genuinely well-formed (a valid `Retry-After`) before ever counting it as truly expected -
// this call does not weaken that check, it only fixes k6's own unrelated built-in metric.
http.setResponseCallback(http.expectedStatuses(200, 429));

const PROFILE = resolveProfile();
const THROUGHPUT = PROFILE === 'throughput';
const VUS = resolveVus('PUBLIC_READ_VUS', 5);
const DURATION = resolveDuration('PUBLIC_READ_DURATION', '30s');

const ENDPOINTS = ['capabilities', 'tests', 'runs-list', 'run-detail'];
const BASE_URL = __ENV.BASE_URL || 'http://web';
// Only the 480 "plain" seeded runs (see performance/seed/seed.sql) - never a runId this scenario
// invented itself, so run-detail genuinely exercises the real seeded dataset.
const PLAIN_RUN_COUNT = 480;

export const options = {
  vus: VUS,
  duration: DURATION,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)', 'count'],
  thresholds: {
    // D4.4.2 - real, locked per-endpoint latency gates, calibrated from four consecutive clean
    // GitHub Actions runs against the exact production-policy lifecycle this workflow runs
    // (34326415182, 34335917122, 34336865449, 34337509597 - see docs/RELEASE_EVIDENCE.md's D4.4.2b
    // section). Each threshold is ~3x the highest p95 actually observed for that endpoint across
    // those four runs, rounded to a clean number - loose enough to absorb normal GitHub-hosted-
    // runner variance without flaking, tight enough to still catch a real regression (a 5-10x
    // slowdown), never the old unfailable `p(95)<100000` placeholder. `runs-list` gets extra
    // headroom (147-216ms observed, the widest run-to-run swing of this scenario's four endpoints).
    'success_latency_ms{endpoint:capabilities}': ['p(95)<250'],
    'success_latency_ms{endpoint:tests}': ['p(95)<300'],
    'success_latency_ms{endpoint:runs-list}': ['p(95)<750'],
    'success_latency_ms{endpoint:run-detail}': ['p(95)<100'],
    // Real, always-enforced correctness gates, in both profiles - an unexpected error is a bug
    // regardless of which profile is running; in throughput mode this is also the primary
    // saturation-detection signal (see recordOutcome: with rateLimitExpected=false, ANY 429 here
    // counts as unexpected, since the raised ceiling should never actually be reached by this
    // scenario's own request volume - only genuine backend saturation would produce one).
    unexpected_error_rate: ['rate==0'],
    unexpected_429: ['count==0'],
    unexpected_5xx: ['count==0'],
  },
};

export function handleSummary(data) {
  return sharedHandleSummary('public-read', ENDPOINTS, data);
}

export default function () {
  // A globally-unique index across every VU (never a per-VU one like __ITER, which would
  // otherwise let all 5 VUs independently restart from perf-run-0001 and mostly re-hit the same
  // small, already-warm subset instead of spreading lookups across the real 480-row dataset).
  const globalIter = exec.scenario.iterationInTest;
  const runId = 'perf-run-' + String(1 + (globalIter % PLAIN_RUN_COUNT)).padStart(4, '0');

  const rateLimitExpected = !THROUGHPUT;
  getAndClassify(
    'pubread',
    'capabilities',
    `${BASE_URL}/api/v1/capabilities`,
    rateLimitExpected,
    validateCapabilities
  );
  getAndClassify(
    'pubread',
    'tests',
    `${BASE_URL}/api/v1/tests?environment=PUBLIC`,
    rateLimitExpected,
    validateTests
  );
  getAndClassify(
    'pubread',
    'runs-list',
    `${BASE_URL}/api/v1/runs`,
    rateLimitExpected,
    validateRunsList
  );
  getAndClassify(
    'pubread',
    'run-detail',
    `${BASE_URL}/api/v1/runs/${runId}`,
    rateLimitExpected,
    validateRunDetail(runId)
  );

  sleep(1);
}

function validateCapabilities(response) {
  const body = response.json();
  return typeof body.apiVersion === 'string' && Array.isArray(body.environments);
}

function validateTests(response) {
  return Array.isArray(response.json().tests);
}

function validateRunsList(response) {
  const body = response.json();
  return Array.isArray(body) && body.length === 500;
}

function validateRunDetail(expectedRunId) {
  return (response) => response.json().runId === expectedRunId;
}
