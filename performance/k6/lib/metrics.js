import { Counter, Rate, Trend } from 'k6/metrics';
import { check } from 'k6';
import { getHeader } from './headers.js';

// D4.4.1b - shared, review-locked classification: an expected 429 must never be counted as a
// failure or allowed to contribute to the real success-path latency distribution, and "expected"
// itself depends on the endpoint (a real rate-limited surface under real load vs. one that should
// never be rate-limited at all, e.g. health) AND on the 429 itself actually being well-formed (a
// genuinely present, positive, integer Retry-After) - a malformed/missing Retry-After is a real
// bug regardless of which surface produced it, never silently accepted as "expected" anyway.
export const expected429 = new Counter('expected_429');
export const unexpected429 = new Counter('unexpected_429');
export const unexpected5xx = new Counter('unexpected_5xx');
export const unexpectedErrorRate = new Rate('unexpected_error_rate');
// One Trend, tagged per endpoint - referenced by name in each scenario's own `options.thresholds`
// (see e.g. public-read.js), which is what actually makes k6 track
// success_latency_ms{endpoint:...} as its own distinct sub-metric at all - tagging a sample alone
// does not. Deliberately populated only for a genuinely valid 200 (status AND content both pass) -
// a 429/5xx response's own duration, or a 200 with the wrong body, must never blend into the real
// success-path p95/p99.
export const successLatencyMs = new Trend('success_latency_ms', true);

// Exported so any scenario needing the identical rule (e.g. sse-connection-cap.js, whose 429 comes
// from a completely different mechanism - SseConnectionsPerIpTracker, not AbuseRateLimitFilter -
// but must be judged by the same real contract: present, positive, integer) never re-implements a
// slightly different version of it.
export function isValidRetryAfter(value) {
  if (value === undefined) {
    return false;
  }
  const n = Number(value);
  return Number.isInteger(n) && n > 0;
}

/**
 * Classifies one HTTP response into the shared metrics above and returns whether it was a
 * genuine, content-valid success.
 *
 * @param {object} response - the k6 http response.
 * @param {string} endpoint - a stable sub-metric tag (e.g. "runs-list").
 * @param {boolean} rateLimitExpected - true for a surface this scenario's own real rate-limit
 *     bucket can legitimately reject under load (public-read/download); false where a 429 would
 *     itself be a bug (health, which AbuseRateLimitFilter does not cover at all).
 * @param {function} [validate] - `(response) => boolean`, a real semantic check of the response
 *     body (never just the status code) - e.g. the runs list actually contains 500 entries, a
 *     run-detail body's own runId matches what was requested, a download's bytes are really a
 *     PNG. A 200 whose content fails this check is never counted as a real success - this is what
 *     catches a misrouted SPA fallback, an empty/wrong list, or a corrupted download that a
 *     status-code-only check would miss entirely. Wrapped in try/catch so a genuinely malformed
 *     body (e.g. HTML where JSON was expected) fails the check rather than crashing the script.
 * @returns {boolean} true only for a genuine, content-valid 200.
 */
export function recordOutcome(response, endpoint, rateLimitExpected, validate) {
  const tags = { endpoint };
  if (response.status === 429) {
    const retryAfterValid = isValidRetryAfter(getHeader(response, 'Retry-After'));
    const isExpected = rateLimitExpected && retryAfterValid;
    (isExpected ? expected429 : unexpected429).add(1, tags);
    check(response, {
      [`${endpoint}: 429 carries a valid Retry-After`]: () => retryAfterValid,
    });
    unexpectedErrorRate.add(!isExpected);
    return false;
  }
  if (response.status >= 500) {
    unexpected5xx.add(1, tags);
    unexpectedErrorRate.add(true);
    return false;
  }
  if (response.status !== 200) {
    unexpectedErrorRate.add(true);
    return false;
  }
  let contentValid;
  try {
    contentValid = typeof validate !== 'function' || validate(response);
  } catch (validationError) {
    contentValid = false;
  }
  check(response, {
    [`${endpoint}: response content passes semantic validation`]: () => contentValid,
  });
  if (!contentValid) {
    unexpectedErrorRate.add(true);
    return false;
  }
  successLatencyMs.add(response.timings.duration, tags);
  unexpectedErrorRate.add(false);
  return true;
}
