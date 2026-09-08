import sse from 'k6/x/sse';
import { Counter, Rate } from 'k6/metrics';
import { check } from 'k6';
import { getHeader } from './lib/headers.js';
import { isValidRetryAfter } from './lib/metrics.js';
import { handleSummary as sharedHandleSummary } from './lib/summary.js';

// D4.4.1c - sse-connection-cap.js: proves the real per-IP concurrent-connection cap
// (SseConnectionsPerIpTracker, default 3 - D3.3) against `perf-hold-open-run` (a still-`RUNNING`
// fixture that never closes on its own - see performance/seed/seed-live-run.sql). 4 VUs open a
// connection at roughly the same time; since every VU in this k6 container shares the same real
// client IP on the Docker network, exactly 3 should be accepted (`200`) and any beyond that
// rejected (`429`, `SseConnectionLimitExceededException`) - no IP-spoofing needed, this is the
// real mechanism under real load. A bounded `timeout` closes each accepted connection after a few
// seconds (this fixture's run never terminates on its own, so something must) - the resulting
// `error` event is expected, not a bug, and is what actually unblocks `sse.open()`.
//
// A review-worthy timing detail found only by running this live, since fixed at its real root
// cause (see seed-live-run.sql's own comment): the fixture now seeds the same two canonical
// RUN_QUEUED/RUN_STARTED events a real RUNNING run always already has by the time anything can
// observe it - a real Spring `SseEmitter` response only flushes actual HTTP headers to the client
// once the first byte goes out, and with those two events now replayed immediately on connect
// (rather than an artificial empty-history fixture waiting on the next periodic heartbeat,
// `runner.sse-heartbeat-interval` = 15s by default), headers arrive right away just like a real
// run - so a short, stable hold-open duration is enough; it no longer needs to exceed the
// heartbeat interval.
const BASE_URL = __ENV.BASE_URL || 'http://web';
const HOLD_OPEN_SECONDS = 5;
const EXPECTED_ACCEPTED = 3;
const EXPECTED_REJECTED = 1;

export const sseAcceptedConnections = new Counter('sse_accepted_connections');
export const sseRejectedConnections = new Counter('sse_rejected_connections');
export const sseUnexpectedStatus = new Counter('sse_unexpected_status');
// A dedicated correctness signal for the 429's own Retry-After contract - present alone is not
// enough (a review finding: it must be a positive integer, the same real rule every other
// scenario's own Retry-After check already enforces via lib/metrics.js's isValidRetryAfter).
export const sseRetryAfterValid = new Rate('sse_retry_after_valid');

export const options = {
  scenarios: {
    concurrentConnections: {
      executor: 'per-vu-iterations',
      vus: 4,
      iterations: 1,
      maxDuration: '30s',
    },
  },
  thresholds: {
    // Real, failing gates on the exact accepted/rejected split - a review finding: Counter
    // metrics alone only describe what happened; without a referencing threshold, a regression
    // that let all 4 connections through (or rejected more than 1) would still exit 0.
    sse_accepted_connections: [`count==${EXPECTED_ACCEPTED}`],
    sse_rejected_connections: [`count==${EXPECTED_REJECTED}`],
    sse_unexpected_status: ['count==0'],
    sse_retry_after_valid: ['rate==1'],
    checks: ['rate==1'],
  },
};

export function handleSummary(data) {
  return sharedHandleSummary('sse-connection-cap', [], data);
}

export default function () {
  const response = sse.open(
    `${BASE_URL}/api/v1/runs/perf-hold-open-run/events`,
    { timeout: `${HOLD_OPEN_SECONDS}s` },
    function (client) {
      client.on('error', function () {
        // Expected: either the deliberate hold-open timeout above (for one of the up-to-3 accepted
        // connections) or an immediate rejection - either way, closing here is what actually
        // unblocks sse.open() (see sse-replay.js's own comment on the extension's blocking design).
        client.close();
      });
    }
  );

  if (response.status === 200) {
    sseAcceptedConnections.add(1);
  } else if (response.status === 429) {
    sseRejectedConnections.add(1);
    // SseConnectionLimitExceededException's own 429 does carry a real Retry-After (confirmed
    // live) - a different mechanism than AbuseRateLimitFilter's own 429s, but judged by the same
    // real contract (present, positive, integer), not just presence.
    const retryAfterValue = getHeader(response, 'Retry-After');
    const retryAfterValid = isValidRetryAfter(retryAfterValue);
    sseRetryAfterValid.add(retryAfterValid);
    check(response, {
      'connection-cap: 429 carries a valid Retry-After': () => retryAfterValid,
    });
  } else {
    sseUnexpectedStatus.add(1, { status: String(response.status) });
  }
}
