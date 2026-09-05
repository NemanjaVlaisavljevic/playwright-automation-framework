# Deployment architecture — Faza D, D0 decisions

Locked decisions from the D0 spike (2026-09-05), each proven against a real running Docker Compose
stack, not just designed on paper - see "Verified" at the end. No VPS purchased yet, per the user's
own explicit sequencing: D0-D4 get built and proven locally/on CI first; only D5 buys the server
(a domain name is the one thing worth buying early, if a good one turns up, since names get taken).

## 1. Production topology

Three long-lived containers, defined in [`deploy/docker-compose.yml`](../deploy/docker-compose.yml).
The base file publishes **no host ports at all** - reaching anything from outside the Compose
network requires either a real reverse-proxy/TLS setup (D1/D5) or the local-only
[`deploy/docker-compose.debug.yml`](../deploy/docker-compose.debug.yml) override (see §5).

```
Internet
    │
Cloudflare
    │
web (Caddy) ──────────────────── edge network ─────────────────── runner-service
    ├── /              → React static files (built at image time, served directly by Caddy)
    └── /api/*         → runner-service (SSE included)

runner-service ─────────────────── data network (internal) ─────── postgres
    └── launches Gradle + JUnit + Playwright/Chromium child processes on demand
        (--no-daemon, one at a time - see RunService/SuiteCommandFactory)
```

**Two separate Compose networks, not one** - `edge` (`web` ↔ `runner-service`) and `data`
(`runner-service` ↔ `postgres`, declared `internal: true`). Without this split every service shares
one default network, and `web` could reach `postgres` directly even though nothing external is
involved - a real gap caught in review before it ever shipped, not assumed safe from the Compose
file's prose alone (see "Verified": confirmed live that `web` genuinely cannot reach `postgres`,
while `runner-service` can).

**No Node process exists in the runtime image at all.** `deploy/web/Dockerfile` is a two-stage
build: a discarded `node:24-slim` stage runs `npm ci && npm run build`, and the shipped image is
plain `caddy:2-alpine` with the resulting `dist/` copied in. Caddy itself:

- serves the dashboard's static files, with SPA fallback to `index.html` (React Router needs every
  path to resolve there, not 404, since routing happens client-side once the app has loaded);
- reverse-proxies `/api/*` to `runner-service`, including long-lived SSE connections (Caddy streams
  a chunked response as it arrives by default - no special buffering config needed, confirmed
  during the spike, see "Verified");
- will terminate TLS once a real domain exists (`SITE_ADDRESS` env var in the Caddyfile, unset
  today defaults to plain `:80`) - **not fully wired yet**: `docker-compose.yml` doesn't pass
  `SITE_ADDRESS` through, publishes no `80`/`443`, and Caddy has no persistent volume for its own
  `/data` (ACME account + certificates) or `/config`. Real TLS is an explicit D1 task list (§5), not
  something this file already does just because the Caddyfile references the env var;
- adds baseline security headers (`X-Content-Type-Options`, `X-Frame-Options`,
  `Referrer-Policy`, and strips the `Server` header).

This was already the intended design, not a new decision - `runner-dashboard/vite.config.ts`'s own
existing comment states the frontend only ever calls relative URLs specifically to match this
same-origin, Caddy-fronted deployment. No frontend code changes were needed to make this work.

`/actuator/*` and `/v3/api-docs` are **not** proxied wholesale. Only `/actuator/health` is exposed
publicly (see `deploy/web/Caddyfile`) - the OpenAPI document is only ever needed against a real
running instance directly during dashboard development (`npm run api:check:contract`), never
through the public edge.

## 2. LOCAL is out of scope for the portfolio deployment

**Decision: the production/portfolio deployment supports `Environment.PUBLIC` only.**
`Environment.LOCAL` is not removed from the domain model - it stays fully supported for local
development, `localTest`, `localJourneyTest`, and `local-sut.yml` in CI, all of which run on a
developer's own machine or a GitHub Actions runner, never in the portfolio deployment. Running
`LOCAL` in production would mean permanently running the separate seven-container Restful Booker
Platform stack (`infra/rbp/`) alongside everything else, with no Docker socket available to start
it on demand (the runner never manages that stack itself, in a container or otherwise - see
`infra/rbp/README.md`'s own "Dashboard `LOCAL` runs" section) - a heavy, standing cost this
deployment's RAM/disk/attack-surface budget doesn't need to carry for a demo that already has
`FIXTURE` as its fast, Docker-free path to the failure/artifact drill-down.

**Design for enforcing this** (not yet implemented - a D1 task, tracked here so it isn't lost):

```
RunCatalog                    - unchanged: every (Environment, Suite) combination this codebase
                                 knows how to run at all.
RunAvailabilityPolicy (new)    - which of those combinations THIS deployment actually allows:
    - local/dev profile: PUBLIC + LOCAL (today's behavior, unchanged)
    - portfolio profile: PUBLIC only
```

`RunRequestValidator` and the `/api/v1/capabilities` endpoint would both filter through the
*intersection* of `RunCatalog` and whichever profile is active (an env var/Spring profile selecting
`RunAvailabilityPolicy`), so the dashboard's own launch form never even renders `LOCAL` as an option
in the portfolio deployment - not a client-side filter the frontend has to apply itself, the
backend simply never advertises it.

Anonymous visitors can browse run history/results. Launch and cancel require authentication (D3).
Whether a rate-limited, anonymous `FIXTURE`-only demo mode is worth adding later is an open
question - the safe default for now is no anonymous launch capability at all.

## 3. PostgreSQL - schema and replay-atomicity protocol (design only, no implementation yet)

Full implementation is D2's job. This section locks the schema shape and the transactional protocol
now so D2 has a settled target rather than a moving one.

### Tables

**`runs`** - replaces the in-memory `ConcurrentHashMap` `RunRepository` backs onto today:

| Column | Notes |
|---|---|
| `run_id` | primary key |
| `environment`, `suite` | |
| `status` | current `RunStatus` |
| `requested_at`, `started_at`, `finished_at` | nullable until reached |
| `exit_code`, `detail` | |
| `process_log_path` | |
| `next_event_sequence` | the row-locked counter every event append allocates from (see below) |
| `version` | optimistic-concurrency column for concurrent lifecycle updates |
| `created_at`, `updated_at` | |

**`run_events`** - the canonical, sequence-numbered journal `FileBackedRunEventJournal`/
`RunEventHub` serve SSE replay from today, moved to Postgres as the durable source of truth (the
raw per-run JSONL the JUnit listener/`Steps` API write stays exactly as it is - original input and
debug evidence, not replaced):

| Column | Notes |
|---|---|
| `run_id`, `sequence` | composite primary key |
| `event_type` | |
| `occurred_at` | |
| `payload` | full event body as `jsonb` |

**`artifacts`** - metadata only, matching `ArtifactManifestEntry` today:

| Column | Notes |
|---|---|
| `artifact_id` | primary key |
| `run_id`, `test_id`, `step_id` (nullable) | |
| `artifact_type` | screenshot/trace/etc. |
| `relative_path`, `size_bytes`, `media_type` | |
| `created_at` | |

The actual screenshot/trace/video/log files themselves stay on the persistent volume
(`/data/runner-artifacts/...`) exactly as today - never inlined into Postgres.

### Replay-atomicity protocol

The exact risk this locks down: a live SSE subscriber must never observe a gap between "here is
everything that already happened" (the replay) and "here is everything from now on" (the live
feed) - either an event gets delivered twice, or (worse) silently dropped, if replay and live
registration aren't strictly ordered against concurrent appends. Single-instance architecture only
(the existing per-run in-process lock, not a distributed one - a multi-instance broker is
explicitly out of scope for this phase):

```
Append (one event being recorded):
  per-run lock
    → INSERT into run_events inside one Postgres transaction, allocating `sequence` from
      runs.next_event_sequence under that row's own lock
    → COMMIT
    → publish the committed event to any live subscribers via the in-process Hub
  unlock

Subscribe (a client connecting, optionally with Last-Event-ID):
  per-run lock
    → SELECT ... FROM run_events WHERE run_id = ? AND sequence > ? ORDER BY sequence  (replay)
    → seed the new subscriber's own delivery mailbox with that replay
    → register the subscriber for live publish
  unlock
```

Holding the same per-run lock across both paths is what makes this safe: a subscribe can never see
a state where an event was appended between the replay query finishing and live registration
starting - either the append's own lock section runs entirely before the subscribe's (subscriber's
replay query then already includes it), or entirely after (subscriber is already registered for
live publish and receives it that way). One or the other, never neither, never both.

Two more invariants carried into D2 as explicit acceptance criteria:

- The lifecycle status transition (e.g. `RUNNING` → `SUCCEEDED`) and its corresponding `RUN_*`
  event insert happen in the **same** database transaction - never a status update that commits
  with no matching event, or vice versa.
- If the service crashes after a `run_events` commit but before the in-process live-publish step,
  nothing is lost: the client's `EventSource` connection drops, the browser reconnects with
  `Last-Event-ID`, and the replay query picks the committed-but-never-live-published event straight
  back up from `run_events`. The DB commit is the real durability boundary, not the in-process
  publish step.

### Restart behavior (explicit rule, not left to chance)

- Every terminal run (`SUCCEEDED`/`FAILED`/`CANCELLED`/`TIMED_OUT`/`ERROR`) is untouched by a
  restart - it's already durable in `runs`/`run_events`/`artifacts`.
- Any run still `QUEUED`/`STARTING`/`RUNNING` at startup transitions to `ERROR` immediately, with a
  `detail` that says plainly the service restarted before the run finished. No attempt is ever made
  to reattach to or resume the old (by now certainly-gone) Gradle/Playwright process tree - the
  external OS process is unrecoverable once the JVM that was tracking it is gone, and pretending
  otherwise would be the actual footgun.
- History, completed events, and artifacts for every other run stay fully readable and replayable
  immediately after restart - a fresh SSE subscriber to an old, already-terminal run just gets its
  full history from `run_events` and then (correctly) never sees a live event, since the run is
  terminal.

## 4. Security boundary

| Surface | Access |
|---|---|
| Run history, results, artifacts (read) | Public, anonymous |
| Launch a run, cancel a run | Requires authentication (D3 - not yet implemented) |
| `/actuator/health` | Public (liveness only) |
| `/actuator/info`, `/v3/api-docs`, any other actuator endpoint | Not proxied publicly at all |
| PostgreSQL | No published port anywhere, on any network - reachable only from `runner-service`, via the `data` network, which is `internal: true` (not even `web` can reach it - see §1) |
| `runner-service` itself | No published port in the base Compose file at all - only reachable through `web`. `docker-compose.debug.yml` publishes a loopback-only debug port for local measurement/troubleshooting; never applied in a real deployment |
| Docker socket | Never mounted into any container - nothing here starts/stops/manages other containers or the host |

## 5. Resource measurement and VPS sizing

Measured with `docker stats` against the real three-container stack above
(`deploy/docker-compose.yml` + the local-only `deploy/docker-compose.debug.yml` override for port
access - see "Verified"). **Corrected after review**: an earlier pass sampled `PUBLIC`/`JOURNEY`
every 5s and reported a ~1.37 GB peak - both choices understated the real number. `Suite.JOURNEY`
excludes `mutation`, and 5 of the 6 journey-tagged classes are `mutation`-tagged - so `PUBLIC`/
`JOURNEY` actually runs just one class (`FeaturedRoomParityTest`), not a representative "heaviest
public path." `PUBLIC`/`REGRESSION` is the real heaviest `PUBLIC` suite (every read-only UI/API/
journey class), and `junit-platform.properties` runs up to **2 test classes concurrently**
(`parallel.config.fixed.parallelism=2`) - meaning up to two Chromium instances can be active at
once, not one. Remeasured against `REGRESSION` with 2s sampling:

| Container | Idle | Peak during an active `REGRESSION` run |
|---|---|---|
| `runner-service` | ~215 MB | **1.70 GB** (real peak, 2s samples - the whole cgroup total: `--no-daemon` build process + forked JUnit worker + up to 2 concurrent Chromium instances, all counted together) |
| `web` (Caddy) | ~14 MB | ~15 MB |
| `postgres` | ~40 MB | ~42 MB (idle - nothing writes to it yet, D2's job; this number will change once D2 lands) |

**System-wide peak today: ~1.75 GB** for the three containers, plus a minimal Linux VPS's own
OS/kernel/sshd baseline (~200-300 MB) - **~2 GB realistic peak right now**, with **zero margin**
for D2's Postgres query/connection load, concurrent dashboard viewers, D4's retention job running
alongside a live suite run, or plain JVM/GC variance - and **no heap caps applied anywhere yet**
(the D1 task from `docs/DEPLOYMENT_SPIKE.md` - `runner-service`'s own `-Xmx`, a `maxHeapSize` on
every `Test` task - is still open). This measurement should be repeated once those caps exist and
D2's persistence path is actually active, before the VPS purchase in D5.

**Disk**, also measured for real, not guessed:

| Item | Size |
|---|---|
| `deploy-runner-service` image | **3.57 GB** (JDK 21 + the entire repository + a pre-warmed Gradle wrapper/dependency cache + Chromium and its OS-level dependencies, all baked in at build time - see §2; there is no smaller "runtime-only" subset to split into a slimmer image, since every one of those is needed at runtime, not just at build time) |
| `deploy-web` image | 89 MB |
| `postgres:17-alpine` image | 424 MB |
| `runner-data` volume, after 3 real runs (1 `FIXTURE`, 1 `REGRESSION`, 1 `JOURNEY`) | 3 MB |

A real VPS needs disk headroom beyond just these image sizes: a rebuild-and-redeploy cycle
transiently holds both the old and new `runner-service` image layers before the old one is pruned
(budget ~4-5 GB free just for that), plus the `runner-data` volume's own unbounded growth until D4
adds a retention policy. **At least 20 GB of disk**, not just the ~4 GB the images alone occupy, is
the realistic floor.

### Go/no-go: 8 GB vs. 12 GB

With the corrected `REGRESSION`-based measurement (~2 GB peak, no heap caps, no D2 load, no safety
margin), a 2 GB box is no longer a credible recommendation on its own:

- **4 GB is the real floor**, not a comfort choice - the measured peak alone is already half of
  that, before any of D2/D4's additional load or normal JVM/GC variance is counted.
- **8 GB is the reasonable pick if choosing specifically between 8 and 12 GB** - a comfortable
  multiple of the measured peak, with real room for D2's Postgres load, concurrent visitors, and
  D4's background retention job running alongside an active suite run.
- **12 GB has no measured justification today** - nothing observed in this spike approaches even
  half of that, with or without a generous safety margin.

This should be **reconfirmed, not assumed**, once D1's heap caps land and D2's persistence path is
actually active (a real Postgres write/read load per run, not an idle container) - re-run this
same `REGRESSION`-suite/2s-sampling measurement then, before the D5 purchase, rather than trusting
this pre-caps, pre-persistence number as final.

### D1 task list carried forward from this section

- Explicit `-Xmx` on `runner-service`'s own launch (`JAVA_TOOL_OPTIONS`, already wired in the
  Dockerfile at `256m` - re-tune once the `REGRESSION` remeasurement above is redone with it
  actually applied) and a `maxHeapSize` on every `Test` task in `build.gradle`.
- Pass `SITE_ADDRESS` through from `docker-compose.yml` to `web` (nothing does today).
- Publish `80`/`443` on `web` (optionally `443/udp` for HTTP/3), replacing
  `docker-compose.debug.yml`'s loopback-only `8081` for real deployment.
- Named volumes for Caddy's own `/data` (ACME account + certificates) and `/config` - without
  these, every container recreate re-requests a fresh certificate and risks hitting Let's Encrypt's
  rate limits.
- Re-run the RAM/disk measurement in §5 with all of the above in place, before the D5 purchase.

## Verified

Every claim above was checked against a real, running Docker Compose stack on this machine, across
two rounds - an initial spike, then a review round that found five real, concrete problems in it
(not style nitpicks), each reproduced and fixed for real, not just reasoned about:

**Round 1 (initial spike):**
- Built both images for real (`docker compose build`) - caught and fixed a real bug: `gradlew` had
  CRLF line endings on this Windows machine's working tree (a stale, pre-`.gitattributes` checkout,
  not a recurrence of the C5.6 fix - confirmed via `git hash-object` the content itself was
  unchanged from `HEAD` once renormalized), which broke its `#!/bin/sh` shebang inside the Linux
  container (`./gradlew: not found`) until fixed.
- Found and fixed a real routing bug via direct curl, not assumed from the Caddyfile: `/api/*` and
  `/actuator/health` were both silently served the React SPA shell instead of being proxied, because
  Caddy evaluates directives in its own fixed internal order (not the order written in the file) -
  `try_files`/`file_server` were rewriting the request to `/index.html` before `reverse_proxy` ever
  saw the original path. Fixed by wrapping the whole site block in one `route { }` block.
- Confirmed SSE actually streams through Caddy in real time (curled `/api/v1/runs/{id}/events`
  directly, watched `RUN_QUEUED`/`RUN_STARTED` events arrive live, not buffered/delayed).
- Launched real suites through the deployed stack's own public API and confirmed artifact/log
  files land on, and survive a container restart via, the named `runner-data` volume (independently
  confirmed from a separate throwaway container mounting the same volume).

**Round 2 (review findings, all five confirmed real and fixed, then reverified against a rebuilt
stack):**
1. **No `.dockerignore` existed** - `deploy/web/Dockerfile`'s `COPY runner-dashboard/ ./` would
   have copied this dev machine's own host `node_modules` (confirmed it exists on disk) over the
   Linux-native one `npm ci` had just installed. Added a root `.dockerignore`
   (`.git`/`.gradle`/`.idea`/`**/build`/`node_modules`/`dist`/`coverage`/`deploy/.env`/runtime
   event-log-artifact directories). Rebuilt and confirmed for real: build-context transfer dropped
   from **215 MB to under 1 MB**, and neither image contains any trace of `runner-dashboard/` node
   tooling (`find`/`which node npm` both came back empty inside each image).
2. **`runner-service`'s host port published even with the debug env var unset** -
   `${RUNNER_SERVICE_DEBUG_PORT:-127.0.0.1:8080}` still published a port either way. Removed
   `ports:` from `runner-service`/`web` in the base `docker-compose.yml` entirely; added
   `deploy/docker-compose.debug.yml` as a separate, clearly-labeled override for local/D0 use only.
3. **`web` could reach `postgres`** - no explicit networks meant all three shared Compose's default
   network. Split into `edge` (`web`↔`runner-service`) and `data` (`runner-service`↔`postgres`,
   `internal: true`). Reverified live after rebuilding: `docker exec deploy-web-1 wget postgres:5432`
   times out (unreachable), `docker exec deploy-runner-service-1` reaching the same address
   succeeds - the isolation is real, not just declared.
4. **Shell-form `CMD` risked `docker stop`/`restart` not reaching the JVM**, and hardcoded a
   version-specific jar filename. Copied the built jar to a fixed `/app/runner-service.jar`, moved
   `-Xmx` to `JAVA_TOOL_OPTIONS` (the JVM reads this directly, no shell expansion needed), and
   switched to exec-form `CMD ["java", "-jar", "/app/runner-service.jar"]`. Reverified live:
   `ps aux` inside the running container shows `java` as PID 1; `docker compose stop
   runner-service` completed in **1.2 seconds** with the log showing Spring's own
   `"Commencing graceful shutdown... Graceful shutdown complete"` - the signal genuinely reaches
   the JVM directly now.
5. **The RAM measurement used the wrong suite and coarse sampling** - see §5's own correction
   above: `PUBLIC`/`JOURNEY` only exercises one class (5 of 6 journey classes are `mutation`-tagged,
   excluded from `Suite.JOURNEY`), and 5s sampling could miss short spikes.
   `PUBLIC`/`REGRESSION` with 2s sampling found a real peak of **1.70 GB**, not the 1.37 GB first
   reported - the go/no-go recommendation in §5 was revised accordingly (4 GB floor, not 2 GB).

Also fixed as part of this round: the Caddyfile's `SITE_ADDRESS` comment previously implied TLS
would "just work" once a domain exists - corrected to explicitly name the three things D1 still has
to wire (env var passthrough, `80`/`443` publication, persistent Caddy `/data`/`/config` volumes),
and `docs/DEPLOYMENT_SPIKE.md` (the preceding, narrower D0 investigation - bare-metal RAM
measurement methodology only, written before any of this section's real Docker artifacts existed)
now carries an explicit superseded notice pointing here, with its own since-reversed "Spring Boot
should serve the frontend" recommendation struck through rather than left to contradict this
document silently.

Cleaned up fully after both rounds: `docker compose down -v` (containers + all named volumes
removed each time), both built images removed, the local `.env` (a spike-only throwaway password)
deleted, confirmed via `docker ps -a`/`docker images` that nothing from either round survives.

**Next: D1 - production packaging for real** (implement `RunAvailabilityPolicy` to actually hide
`LOCAL` from the portfolio deployment's capabilities/validator, the TLS task list above, re-run the
RAM/disk measurement with heap caps and D2's persistence path active, and fold this spike's
Dockerfiles/Compose files into the real deployment artifacts rather than throwaway ones - they
already work, across two full review-and-fix rounds now, not a prototype to discard).
