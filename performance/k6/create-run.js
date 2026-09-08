import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { getHeader } from './lib/headers.js';
import { isValidRetryAfter } from './lib/metrics.js';
import { handleSummary as sharedHandleSummary } from './lib/summary.js';

// D4.4.1d - create-run.js: the one genuinely admin-gated, real-process-launching scenario. Never
// load-tested like a read-path endpoint - `POST /api/v1/runs` is deliberately single-worker,
// launches a real Gradle/Playwright process, and is rate-limited 3/min+10/h. This scenario
// instead performs a real GitHub OAuth2 admin login against the `wiremock` stub (never a fake
// ClientRegistrationRepository - mirrors OAuthFlowE2eTest's own established pattern: stub only
// the external dependency, keep the app's own real OAuth2 binding/wiring), measures one real `202
// Accepted` latency, consumes the remaining per-minute rate-limit budget with two deliberately
// Bean-Validation-invalid requests (an oversized testKeys list - fails before RunService.submit is
// ever reached, so no extra Gradle process launches), confirms a 4th request in the same window is
// rejected, and explicitly cancels the one real run it launched - never left running into
// teardown.
//
// Runs against the `k6` service (not `k6-sse`) - no SSE involved here.
const BASE_URL = __ENV.BASE_URL || 'http://web';
// Must match performance/wiremock/mappings/user-info.json's own "id" and
// deploy/performance.env's own RUNNER_SECURITY_ADMIN_GITHUB_ID.
const EXPECTED_ADMIN_GITHUB_ID = 999001;

export const createRunSuccessLatencyMs = new Trend('create_run_success_latency_ms', true);
export const createRunUnexpectedErrorRate = new Rate('create_run_unexpected_error_rate');
// A dedicated, single-sample correctness signal (this scenario runs once, 1 VU/1 iteration) -
// true only when every one of the real steps below succeeded: OAuth2 login established a real
// admin session, the one valid request got a real 202, both deliberately-invalid requests got
// 400, the 4th request in the same window got 429 with a valid Retry-After, and the launched run
// was actually cancelled afterward. A review-established pattern (D4.4.1b/c): a `check()` alone
// never fails the k6 process without a real threshold referencing it.
export const createRunCorrectness = new Rate('create_run_correctness');

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    create_run_correctness: ['rate==1'],
    create_run_unexpected_error_rate: ['rate==0'],
    checks: ['rate==1'],
  },
};

export function handleSummary(data) {
  return sharedHandleSummary('create-run', [], data);
}

export default function () {
  const outcome = { adminSessionEstablished: false };

  // --- Real GitHub OAuth2 admin login against the wiremock stub ---
  // Step 1: the real authorization endpoint. Establishes the session + a server-side-stored
  // OAuth2AuthorizationRequest (with its own `state`), then 302s to the stub. k6's own cookie jar
  // (enabled globally by default for a VU) carries the resulting session cookie through every
  // subsequent hop automatically - only the redirect chain itself is followed manually, since it
  // genuinely crosses from the real backend to the stub and back.
  let response = http.get(`${BASE_URL}/api/v1/auth/oauth2/authorization/github`, {
    redirects: 0,
  });
  const authorizeOk = check(response, {
    'oauth: authorization redirects to the stub': (r) => r.status === 302,
  });

  // Step 2: the stub's own authorize endpoint - a plain, templated 302 straight back to the real
  // callback URL with a real, session-matching code/state (see
  // performance/wiremock/mappings/authorize.json).
  response = http.get(getHeader(response, 'Location'), { redirects: 0 });
  const stubRedirectOk = check(response, {
    'oauth: stub redirects to the real callback': (r) => r.status === 302,
  });

  // Step 3: the real callback. Performs the real server-to-server token exchange and user-info
  // lookup against the stub, then 302s to the login success/failure handler's own target ("/") -
  // both success and failure look identical at this layer (SecurityConfig's own success/failure
  // handlers both redirect to "/"), so the actual outcome is only knowable via /api/v1/auth/me.
  response = http.get(getHeader(response, 'Location'), { redirects: 0 });
  const callbackOk = check(response, {
    'oauth: callback completes and redirects': (r) => r.status === 302,
  });

  response = http.get(`${BASE_URL}/api/v1/auth/me`);
  const me = safeJson(response);
  outcome.adminSessionEstablished = authorizeOk && stubRedirectOk && callbackOk && me !== null
    && me.canManageRuns === true;
  check(me, {
    'oauth: real admin session established (canManageRuns)': () => outcome.adminSessionEstablished,
  });

  // --- The one valid request - measures the real 202 Accepted latency ---
  let csrfToken = primeCsrf(); // must (re-)prime after login - the session rotated on success.
  response = http.post(
    `${BASE_URL}/api/v1/runs`,
    JSON.stringify({ environment: 'PUBLIC', suite: 'SMOKE' }),
    { headers: jsonHeaders(csrfToken) }
  );
  createRunSuccessLatencyMs.add(response.timings.duration);
  const validRequestOk = check(response, {
    'create-run: the one valid request returns 202': (r) => r.status === 202,
  });
  const launchedRunId = validRequestOk ? safeJson(response)?.runId : null;

  // --- Two deliberately Bean-Validation-invalid requests - consume the remaining per-minute
  // budget without ever reaching RunService.submit (an oversized testKeys list fails
  // @Size(max=25) inside Spring's own request-binding layer, before the controller method body -
  // and therefore any real Gradle process launch - is ever reached). ---
  let invalidRequestsOk = true;
  for (let i = 1; i <= 2; i++) {
    response = http.post(`${BASE_URL}/api/v1/runs`, oversizedTestKeysBody(), {
      headers: jsonHeaders(csrfToken),
    });
    invalidRequestsOk =
      check(response, {
        [`create-run: deliberately-invalid request ${i} of 2 returns 400`]: (r) => r.status === 400,
      }) && invalidRequestsOk;
  }

  // --- A 4th request in the same one-minute window must now be rate-limited ---
  response = http.post(`${BASE_URL}/api/v1/runs`, oversizedTestKeysBody(), {
    headers: jsonHeaders(csrfToken),
  });
  const retryAfterValid = isValidRetryAfter(getHeader(response, 'Retry-After'));
  const rateLimitOk = check(response, {
    'create-run: the 4th request in the same window is rate-limited (429)': (r) => r.status === 429,
    'create-run: 429 carries a valid Retry-After': () => retryAfterValid,
  });

  // --- Cleanup: cancel whatever run was actually launched - never left running into teardown ---
  let cleanupOk = true;
  if (launchedRunId) {
    response = http.post(`${BASE_URL}/api/v1/runs/${launchedRunId}/cancel`, null, {
      headers: { 'X-XSRF-TOKEN': csrfToken },
    });
    cleanupOk = check(response, {
      'create-run: cleanup cancel of the launched run succeeded': (r) => r.status === 200,
    });
  } else {
    cleanupOk = false;
  }

  const correct =
    outcome.adminSessionEstablished &&
    validRequestOk &&
    invalidRequestsOk &&
    rateLimitOk &&
    cleanupOk;
  createRunCorrectness.add(correct);
  createRunUnexpectedErrorRate.add(!correct);
}

function primeCsrf() {
  const response = http.get(`${BASE_URL}/api/v1/auth/csrf`);
  check(response, { 'csrf: primed (204)': (r) => r.status === 204 });
  const cookies = http.cookieJar().cookiesForURL(`${BASE_URL}/`);
  return cookies['XSRF-TOKEN'] ? cookies['XSRF-TOKEN'][0] : undefined;
}

function jsonHeaders(csrfToken) {
  return { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': csrfToken };
}

function oversizedTestKeysBody() {
  const testKeys = [];
  for (let i = 0; i < 26; i++) {
    testKeys.push('dev.vlaisanem.automation.tests.api.PerfSeedTest#test' + i);
  }
  return JSON.stringify({ environment: 'PUBLIC', suite: 'CUSTOM', testKeys });
}

function safeJson(response) {
  try {
    return response.json();
  } catch (parseError) {
    return null;
  }
}
