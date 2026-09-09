import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { getAndClassify } from './lib/http.js';
import { getHeader } from './lib/headers.js';
import { handleSummary as sharedHandleSummary, SUMMARY_TREND_STATS } from './lib/summary.js';
import { resolveProfile, resolveVus, resolveDuration } from './lib/profile.js';

// D4.4.1b - artifact-reads.js: the artifacts-list and artifact-download endpoints, which share
// the real 30/min `download` rate-limit bucket (AbuseRateLimitFilter) - a much lower ceiling than
// public-read.js's 120/min, so this scenario deliberately runs a lighter load profile even in the
// production-policy pass. Both 200 and 429 are told to k6 as expected statuses (see
// public-read.js's own comment for why) - the independent custom classification still separately
// verifies a 429's own Retry-After.
//
// D4.4.2 - also runs as a throughput pass against
// deploy/docker-compose.performance-throughput.yml's raised download-rate-limit - see
// public-read.js's own D4.4.2 comment for the shared reasoning (a 429 is then itself a bug) and
// performance/README.md for the stepped-escalation methodology.
http.setResponseCallback(http.expectedStatuses(200, 429));

const PROFILE = resolveProfile();
const THROUGHPUT = PROFILE === 'throughput';
const VUS = resolveVus('ARTIFACT_READS_VUS', 2);
const DURATION = resolveDuration('ARTIFACT_READS_DURATION', '30s');

const ENDPOINTS = ['artifacts-list', 'artifact-download'];
const BASE_URL = __ENV.BASE_URL || 'http://web';
// The five runs D4.4.1a's seed actually attached a real artifact to (perf-run-0001..0005, see
// performance/seed/seed.sql) - never an id this scenario invented itself.
const ARTIFACT_RUN_COUNT = 5;
const REAL_ARTIFACT_SIZE_BYTES = 68;
const PNG_MAGIC = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];

export const options = {
  vus: VUS,
  duration: DURATION,
  summaryTrendStats: SUMMARY_TREND_STATS,
  thresholds: {
    // D4.4.2 - real, locked latency gates, ~3x the highest p95 observed for each endpoint across
    // four consecutive clean GitHub Actions runs (see public-read.js's own comment for the full
    // calibration methodology and the exact run ids).
    'success_latency_ms{endpoint:artifacts-list}': ['p(95)<75'],
    'success_latency_ms{endpoint:artifact-download}': ['p(95)<50'],
    // Real, always-enforced correctness gates - see public-read.js's own comment on why these hold
    // in both profiles, and why unexpected_429 doubles as the primary throughput-mode saturation
    // signal.
    unexpected_error_rate: ['rate==0'],
    unexpected_429: ['count==0'],
    unexpected_5xx: ['count==0'],
  },
};

export function handleSummary(data) {
  return sharedHandleSummary('artifact-reads', ENDPOINTS, data);
}

export default function () {
  // A globally-unique index across every VU - see public-read.js's own comment on why __ITER
  // alone would be wrong here too.
  const index = 1 + (exec.scenario.iterationInTest % ARTIFACT_RUN_COUNT);
  const runId = 'perf-run-' + String(index).padStart(4, '0');
  const artifactId = 'perf-artifact-' + String(index).padStart(4, '0');

  const rateLimitExpected = !THROUGHPUT;
  getAndClassify(
    'artread',
    'artifacts-list',
    `${BASE_URL}/api/v1/runs/${runId}/artifacts`,
    rateLimitExpected,
    validateArtifactsList(artifactId)
  );
  getAndClassify(
    'artread',
    'artifact-download',
    `${BASE_URL}/api/v1/runs/${runId}/artifacts/${artifactId}`,
    rateLimitExpected,
    validateArtifactDownload,
    { responseType: 'binary' }
  );

  sleep(1);
}

function validateArtifactsList(expectedArtifactId) {
  return (response) => {
    const body = response.json();
    return Array.isArray(body) && body.some((entry) => entry.artifactId === expectedArtifactId);
  };
}

function validateArtifactDownload(response) {
  if (getHeader(response, 'Content-Type') !== 'image/png') {
    return false;
  }
  const bytes = new Uint8Array(response.body);
  if (bytes.length !== REAL_ARTIFACT_SIZE_BYTES) {
    return false;
  }
  for (let i = 0; i < PNG_MAGIC.length; i++) {
    if (bytes[i] !== PNG_MAGIC[i]) {
      return false;
    }
  }
  return true;
}
