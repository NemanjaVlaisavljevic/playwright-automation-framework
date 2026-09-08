import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { getHeader } from './headers.js';
import { recordOutcome } from './metrics.js';

/**
 * Performs one GET, carrying a deterministic `X-Request-ID` unique to this scenario/global-
 * iteration/endpoint (`exec.scenario.iterationInTest` - a globally-unique counter across every VU
 * in this scenario, never a per-VU one like `__ITER`, which would otherwise let every VU
 * independently restart from the same value) - mirrors D4.3.3's own real requestId correlation, so
 * this exact k6 request can later be matched to its own real ECS log line by that exact id, never
 * merely inferred by matching route+status+rough timing. Verifies the response actually echoed
 * that same id back before classifying the outcome via {@link recordOutcome}.
 *
 * @param {string} scenario - a short scenario prefix (e.g. "pubread").
 * @param {string} endpoint - a stable sub-metric tag (e.g. "runs-list").
 * @param {string} url
 * @param {boolean} rateLimitExpected - see recordOutcome.
 * @param {function} [validate] - see recordOutcome.
 * @param {object} [extraParams] - additional k6 request params (e.g. `{responseType: 'binary'}`
 *     for a real binary-content check).
 */
export function getAndClassify(scenario, endpoint, url, rateLimitExpected, validate, extraParams) {
  const requestId = `perf-${scenario}-i${exec.scenario.iterationInTest}-${endpoint}`;
  const params = Object.assign(
    { headers: { 'X-Request-ID': requestId }, tags: { endpoint } },
    extraParams || {}
  );
  const response = http.get(url, params);
  check(response, {
    [`${endpoint}: X-Request-ID echoed unchanged`]: (r) =>
      getHeader(r, 'X-Request-ID') === requestId,
  });
  recordOutcome(response, endpoint, rateLimitExpected, validate);
  return response;
}
