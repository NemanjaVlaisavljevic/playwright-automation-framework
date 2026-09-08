import http from 'k6/http';
import { sleep } from 'k6';
import { getAndClassify } from './lib/http.js';
import { handleSummary as sharedHandleSummary } from './lib/summary.js';
import { resolveProfile, resolveVus, resolveDuration } from './lib/profile.js';

// D4.4.1b - health.js: liveness/readiness, measured separately from the read-path scenarios - a
// very different latency profile (D4.3.1's own liveness/readiness design), and a route
// AbuseRateLimitFilter does not cover at all. Only 200 is told to k6 as an expected status - a 429
// here would itself be a real bug, never an expected signal the way it is for
// public-read.js/artifact-reads.js (see lib/metrics.js's own recordOutcome, rateLimitExpected=
// false below) - true in both D4.4.2 profiles, since this route is never rate-limited either way.
//
// D4.4.2 - HEALTH_VUS/HEALTH_DURATION let a throughput run push heavier concurrent traffic too
// (checking liveness/readiness itself never degrades under load elsewhere) - PERFORMANCE_PROFILE
// is still validated here for a consistent invocation contract across every scenario, even though
// it changes nothing about this one's own classification.
http.setResponseCallback(http.expectedStatuses(200));

resolveProfile();
const VUS = resolveVus('HEALTH_VUS', 2);
const DURATION = resolveDuration('HEALTH_DURATION', '15s');

const ENDPOINTS = ['liveness', 'readiness'];
const BASE_URL = __ENV.BASE_URL || 'http://web';

export const options = {
  vus: VUS,
  duration: DURATION,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)', 'count'],
  thresholds: {
    'success_latency_ms{endpoint:liveness}': ['p(95)<100000'],
    'success_latency_ms{endpoint:readiness}': ['p(95)<100000'],
    unexpected_error_rate: ['rate==0'],
    unexpected_429: ['count==0'],
    unexpected_5xx: ['count==0'],
  },
};

export function handleSummary(data) {
  return sharedHandleSummary('health', ENDPOINTS, data);
}

export default function () {
  getAndClassify(
    'health',
    'liveness',
    `${BASE_URL}/actuator/health/liveness`,
    false,
    validateHealth
  );
  getAndClassify(
    'health',
    'readiness',
    `${BASE_URL}/actuator/health/readiness`,
    false,
    validateHealth
  );

  sleep(1);
}

function validateHealth(response) {
  return response.json().status === 'UP';
}
