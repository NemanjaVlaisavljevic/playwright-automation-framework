// D4.4.2 - shared parameter/profile resolution for every scenario that supports both a
// production-policy pass (real RunnerProperties rate limits, a 429 on the limited surfaces is
// itself the expected outcome under enough load) and a throughput pass (limits raised via
// deploy/docker-compose.performance-throughput.yml, so a 429 there is itself a bug - see
// lib/metrics.js's own recordOutcome and its rateLimitExpected flag). Centralized here so no
// scenario re-implements slightly different parsing/validation, and so a stray/misspelled env var
// fails loudly at script-load time rather than silently falling back to a default.
//
// Deliberately scoped to the read-path HTTP scenarios this file's own callers are
// (public-read.js/artifact-reads.js/health.js) - sse-connection-cap.js exists specifically to
// prove the real, fixed per-IP connection cap (a security mechanism, not a throughput knob - it
// must never vary by profile), and create-run.js is deliberately never load-tested at all (see its
// own header comment) - neither imports this module.

const DURATION_PATTERN = /^(\d+(?:\.\d+)?(?:ms|s|m|h))+$/;
const MAX_VUS = 500; // a sane guard against an accidental extra zero, not a real system ceiling

export function resolveProfile() {
  const profile = __ENV.PERFORMANCE_PROFILE || 'production-policy';
  if (profile !== 'production-policy' && profile !== 'throughput') {
    throw new Error(
      `PERFORMANCE_PROFILE must be "production-policy" or "throughput" - got: "${profile}"`
    );
  }
  return profile;
}

export function resolveVus(envVarName, defaultVus) {
  const raw = __ENV[envVarName];
  if (raw === undefined) {
    return defaultVus;
  }
  const vus = Number(raw);
  if (!Number.isInteger(vus) || vus < 1 || vus > MAX_VUS) {
    throw new Error(`${envVarName} must be a positive integer <= ${MAX_VUS} - got: "${raw}"`);
  }
  return vus;
}

export function resolveDuration(envVarName, defaultDuration) {
  const raw = __ENV[envVarName];
  if (raw === undefined) {
    return defaultDuration;
  }
  if (!DURATION_PATTERN.test(raw)) {
    throw new Error(
      `${envVarName} must be a valid k6 duration (e.g. "30s", "2m") - got: "${raw}"`
    );
  }
  return raw;
}
