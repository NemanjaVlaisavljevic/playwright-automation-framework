# D4.4 - Performance baseline tooling

k6 load-test scenarios, the deterministic database seed, and the summarizer/dashboard-baseline
tooling for the performance-baseline phase - see
`docs/DEPLOYMENT_ARCHITECTURE.md`/`docs/RELEASE_EVIDENCE.md` for the full design/results and
`deploy/docker-compose.performance.yml` for the isolated Compose overlay this all runs under.

## Layout

- `seed/` - the one-shot `performance-seed` Compose service (D4.4.1a): a deterministic, idempotent
  SQL seed (`seed.sql`) plus a fail-closed shell entrypoint (`seed.sh`) that refuses to run against
  anything but the dedicated `runner_performance` database, writes real artifact files (a genuine
  small PNG, `seed/assets/fixture.png`) onto the mounted `runner-data` volume *before* the database
  transaction that references them, and runs a runtime acceptance check proving the seeded event
  timeline never regresses in time. Also backs `performance-seed-live` (D4.4.1c) via
  `seed-live-run.sh`/`seed-live-run.sql` - inserts/deletes `perf-hold-open-run` (a still-`RUNNING`
  fixture the SSE connection-cap scenario needs), gated on `runner-service` already being healthy
  (i.e. its own startup recovery pass already complete).
- `k6/` - k6 scenario scripts: `public-read.js` (capabilities/tests/runs-list/run-detail, the
  120/min `public-read` rate-limit bucket), `artifact-reads.js` (artifacts-list/artifact-download,
  the 30/min `download` bucket), `health.js` (liveness/readiness, never rate-limited) - all D4.4.1b.
  `sse-replay.js` (connection-establish time, time-to-first-event, full replay of
  `perf-replay-run`'s 399 seeded events) and `sse-connection-cap.js` (the real per-IP 3-connection
  cap - 4 VUs, exactly 3 accepted + 1 rejected) - both D4.4.1c, requiring the custom `k6-sse`
  image. `create-run.js` (D4.4.1d) - the one genuinely admin-gated, real-process-launching scenario:
  a real GitHub OAuth2 admin login against the `wiremock` stub, one real `202` latency
  measurement, two deliberately Bean-Validation-invalid requests consuming the remaining per-minute
  rate-limit budget, a 4th request proving `429`, and an explicit cleanup cancel of the launched
  run. `k6/lib/metrics.js` classifies every HTTP response into shared expected/unexpected
  429/5xx counters plus a success-only latency `Trend`; `k6/lib/summary.js` is the one
  `handleSummary()` every scenario shares, so their JSON output shape (and the exact k6 version
  producing it) stays locked together. `k6/lib/profile.js` (D4.4.2) resolves
  `PERFORMANCE_PROFILE`/per-scenario VU/duration env vars for `public-read.js`/`artifact-reads.js`/
  `health.js` - see "Throughput profile" below. `k6/results/` is gitignored - ephemeral local
  output only.
- `k6-sse/` - a custom k6 build with the `xk6-sse` extension baked in (stock k6 has no native SSE
  support) - every version pinned as precisely as this actually gets (k6 v1.5.0, xk6 v0.13.4,
  `xk6-sse` v0.1.12 - a real tagged release, never a floating branch/commit; both base images
  additionally pinned by their exact content digest, not just a version tag). This makes *running*
  the already-built image offline/reproducible - the *build* itself still needs network access to
  fetch Go module dependencies and is not guaranteed byte-for-byte identical across rebuilds; see
  the Dockerfile's own header comment for the precise, non-overstated claim.
- `wiremock/mappings/` - stub mappings standing in for github.com's own OAuth2 endpoints
  (authorize/token/user-info), used only by `create-run.js` - see
  `deploy/docker-compose.performance-auth.yml`'s own `wiremock` service comment. That file (not
  `docker-compose.performance.yml` itself) is where `wiremock` and the OAuth-provider-URI/
  non-Secure-cookie overrides live - kept deliberately separate so D4.4.1a-c's own scenarios still
  run under the real, unmodified `PORTFOLIO`/Secure-cookie posture (see that file's own header
  comment for why, and the exact recreate/run/revert sequence step 3c below requires).

## Running the isolated performance stack

The Compose project name is fixed at `runner-performance` by `deploy/docker-compose.performance.yml`'s
own top-level `name:` field - isolation from the real deployment does not depend on remembering
`-p runner-performance` on the command line, though passing it explicitly is harmless and still
recommended for clarity. **Always** with `deploy/performance.env` (never `deploy/.env`, the real
deployment's own gitignored secrets file).

Several separate steps, not one combined `up` - a one-shot seed service must never be allowed to
tear the rest of the stack down the instant it exits (see the overlay file's own header comment):

```bash
# 1. Bring up the long-lived services - the REAL, unmodified runner-service (real PORTFOLIO
# profile, real Secure session cookie). wiremock is NOT part of this step - it only ever comes up
# later, in step 3c, under a separate auth-only override file (see below):
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.performance.yml \
  --env-file deploy/performance.env up -d --build postgres runner-service

# 2. Run the one-shot seed to completion (safe to repeat against the same stack):
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.performance.yml \
  --env-file deploy/performance.env run --rm --build performance-seed

# 3. Run k6 scenarios against the still-live stack - the real topology (k6 -> Caddy ->
# runner-service -> PostgreSQL), so `web` joins first:
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.performance.yml \
  --env-file deploy/performance.env up -d --build web
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.performance.yml \
  --env-file deploy/performance.env run --rm --build k6 run /scripts/public-read.js
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.performance.yml \
  --env-file deploy/performance.env run --rm --build k6 run /scripts/artifact-reads.js
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.performance.yml \
  --env-file deploy/performance.env run --rm --build k6 run /scripts/health.js
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.performance.yml \
  --env-file deploy/performance.env run --rm --build k6-sse run /scripts/sse-replay.js

# 3b. For sse-connection-cap.js specifically: insert the live-run fixture only now (never before
# step 1), run the scenario, then always delete the fixture again afterward:
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.performance.yml \
  --env-file deploy/performance.env run --rm --build performance-seed-live \
  ./seed-live-run.sh insert
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.performance.yml \
  --env-file deploy/performance.env run --rm --build k6-sse run /scripts/sse-connection-cap.js
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.performance.yml \
  --env-file deploy/performance.env run --rm performance-seed-live ./seed-live-run.sh delete

# 3c. create-run.js last (rate-limited 3/min - running it twice within the same minute against the
# same admin id will genuinely fail on the second attempt; this is correct, expected behavior, not
# a bug - wait a real 60s between runs if repeating). Needs the separate OAuth-only override file
# (deploy/docker-compose.performance-auth.yml) - never run this against the plain stack above,
# and always recreate runner-service back to its real config afterward (never leave the override
# applied into step 4's teardown or into any later reuse of this same stack):
#
#   a. Recreate runner-service WITH the override added, and bring up wiremock:
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.performance.yml \
  -f deploy/docker-compose.performance-auth.yml --env-file deploy/performance.env \
  up -d --build runner-service wiremock
#   (wait for runner-service to report healthy again - this recreate re-runs its startup
#   recovery pass, safe here only because no fixture is left RUNNING by this point)
#
#   b. Run the scenario against this now-LOCAL_DEV/non-Secure-cookie instance:
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.performance.yml \
  -f deploy/docker-compose.performance-auth.yml --env-file deploy/performance.env \
  run --rm --build k6 run /scripts/create-run.js
#
#   c. Revert runner-service back to the real, unmodified config - WITHOUT the override file:
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.performance.yml \
  --env-file deploy/performance.env up -d --build runner-service
#   (wait for healthy again before moving on - real PORTFOLIO + Secure cookie restored)

# 4. Tear down (fully throwaway - volumes intentionally removed). Include the auth-only override
# file here too (so Compose still knows about `wiremock`, even though it was only ever brought up
# via that file back in step 3c) and `--profile tools` - required, not optional (found live):
# `down` is just as profile-scoped as `up`, so without it a still-running profiled service is
# silently left behind instead of torn down:
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.performance.yml \
  -f deploy/docker-compose.performance-auth.yml --env-file deploy/performance.env \
  --profile tools down -v
```

**Why the OAuth override is a separate file, not part of `docker-compose.performance.yml` itself**:
D4.4's own plan calls for a later review checkpoint that runs "one production-policy (real,
unmodified `deploy/docker-compose.yml`) measurement-only pass across every scenario." If the
`LOCAL_DEV`/non-Secure-cookie override needed only by `create-run.js` lived in the
always-applied performance overlay, D4.4.1a-c's own read-path/SSE scenarios would silently also run
under that weaker posture instead of the real `PORTFOLIO`/Secure-cookie one they were actually
validated against - which the checkpoint's own "unmodified" framing does not tolerate. Keeping it
in `deploy/docker-compose.performance-auth.yml`, applied only around step 3c and reverted
immediately after, means every other scenario - and the checkpoint itself - runs against the real
security posture throughout. `create-run.js`'s own results are therefore an authenticated
write-path measurement under an isolated HTTP (non-TLS) test override, not a fully
production-identical TLS/OAuth result; real Secure-cookie/TLS acceptance for the write path is
deferred to D5.

## Throughput profile (D4.4.2)

Every scenario above runs "production-policy" by default (`PERFORMANCE_PROFILE=production-policy`,
the implicit default) - the real `AbuseRateLimitFilter` limits stay on, and an expected `429` under
enough concurrent load is a correct outcome, not a bug. `public-read.js`/`artifact-reads.js`/
`health.js` also support a **throughput** profile - limits raised via a separate override file
(`deploy/docker-compose.performance-throughput.yml`) so the *real backend*, not the anti-abuse
limiter, is what gets measured. `sse-connection-cap.js` and `create-run.js` deliberately have no
throughput mode: the former exists specifically to prove the real, fixed per-IP SSE cap (which must
never vary by profile), and the latter is never load-tested at all (see its own header comment) -
raising rate limits for either would be testing a fundamentally different thing, not a heavier
version of the same thing.

In throughput mode a `429` is itself a bug (the raised ceiling should never actually be reached by
the scenario's own request volume - see `lib/http.js`'s `rateLimitExpected` flag, now
`!THROUGHPUT` in these three scripts), and `unexpected_error_rate`/`unexpected_429`/
`unexpected_5xx` all carry a real `count==0`/`rate==0` threshold in both profiles - a genuinely
useful saturation signal in throughput mode specifically, since any 429 there means real
contention, not the rate limiter doing its job.

Each of the three scripts takes its own VU/duration env vars (`PUBLIC_READ_VUS`/
`PUBLIC_READ_DURATION`, `ARTIFACT_READS_VUS`/`ARTIFACT_READS_DURATION`, `HEALTH_VUS`/
`HEALTH_DURATION`) - deliberately per-scenario, never one shared `VUS`, so raising load on one
scenario can never accidentally also raise it on SSE or create-run (which don't read these vars at
all). `lib/profile.js` validates every value strictly (a positive integer VU count within a sane
ceiling, a real k6 duration string) and fails the script load immediately on anything else.

```bash
# Bring up the base stack (steps 1-3 above), then apply the throughput override on top - a real
# Compose recreate, same pattern as the auth override:
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.performance.yml \
  -f deploy/docker-compose.performance-throughput.yml --env-file deploy/performance.env \
  up -d --build runner-service
# (wait healthy again - this recreate re-runs the startup recovery pass, same caveat as the auth
# override's own sequence)

# Stepped VU escalation - never jump straight to one aggressive run. Start low, watch for real
# saturation (elevated p95/p99, HikariCP connections_active approaching connections_max,
# connections_pending > 0, JVM heap climbing without recovering, or any unexpected_error_rate/
# unexpected_429/unexpected_5xx > 0), and stop increasing the moment any of those appear - the
# point is a genuine capacity profile, not a single number from one aggressive run:
docker compose ... run --rm --build k6 run -e PERFORMANCE_PROFILE=throughput \
  -e PUBLIC_READ_VUS=10 -e PUBLIC_READ_DURATION=30s /scripts/public-read.js
# check HikariCP/heap between steps (published web port never exposes /actuator/prometheus - a
# deliberate D4.3.2 decision - so this reads it from inside the container):
docker exec runner-performance-runner-service-1 wget -qO- \
  http://127.0.0.1:8080/actuator/prometheus | grep hikaricp_connections
docker compose ... run --rm --build k6 run -e PERFORMANCE_PROFILE=throughput \
  -e PUBLIC_READ_VUS=20 -e PUBLIC_READ_DURATION=30s /scripts/public-read.js
docker compose ... run --rm --build k6 run -e PERFORMANCE_PROFILE=throughput \
  -e PUBLIC_READ_VUS=40 -e PUBLIC_READ_DURATION=30s /scripts/public-read.js
# ... continue only while no saturation signal has appeared yet.

# Revert runner-service back to the real, unmodified config afterward - WITHOUT the override file,
# same pattern as the auth override's own revert step:
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.performance.yml \
  --env-file deploy/performance.env up -d --build runner-service
```

**A real, local-machine finding, not yet a locked threshold**: stepped 10 -> 20 -> 40 -> 80 VUs
against `public-read.js` on this dev machine showed no saturation at all through 80 VUs - `checks`
stayed 1:1, zero unexpected errors/429s/5xx throughout, HikariCP's pool never left 0 active/0
pending connections, and per-endpoint p95/p99 degraded gracefully (single-digit-to-low-tens of ms)
rather than spiking. This is expected and does not mean 80 VUs is "the" real capacity number - a
local dev laptop under Docker Desktop is not the real deployment target, and this project's own
plan deliberately defers locking real thresholds to actual CI-runner measurements (see the next
section) rather than trusting a number any single machine produces. This local pass's job was only
to prove the throughput mechanism itself - the overlay, the profile-aware classification, the
per-scenario VU/duration knobs - genuinely works end to end, which it does.
