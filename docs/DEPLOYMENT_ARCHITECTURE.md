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

**Two separate Compose networks, not one** - `edge` (`web`, `runner-service`) and `data`
(`runner-service`, `postgres`). **What actually stops `web` reaching `postgres` is topology, not
`internal: true`**: `web` is attached only to `edge`, `postgres` is attached only to `data`, and
Docker containers can only resolve/reach each other by name over a network they are *both* attached
to - `runner-service` is the only service on both, so it alone bridges the two. Without this split
(every service sharing one default network instead) `web` could reach `postgres` directly even
though nothing external is involved (see "Verified" for the live confirmation that `web` genuinely
cannot reach `postgres`, while `runner-service` can). `data`'s own `internal: true` is a
*separate*, additional protection on top of that topology, not the mechanism behind it: it stops the
`data` network itself from routing to the external internet/host gateway at all (so even
`runner-service` or `postgres` could never reach out through it), which is a real defense-in-depth
property but is not what would save this boundary if `web` were ever mistakenly also attached to
`data` - at that point `internal: true` would do nothing to stop `web`↔`postgres` traffic, since
`internal` only restricts a network's *external* connectivity, never which of its own already-joined
members can reach each other.

**No Node process exists in the runtime image at all.** `deploy/web/Dockerfile` is a two-stage
build: a discarded `node:24-slim` stage runs `npm ci && npm run build`, and the shipped image is
plain `caddy:2-alpine` with the resulting `dist/` copied in. Caddy itself:

- serves the dashboard's static files, with SPA fallback to `index.html` (React Router needs every
  path to resolve there, not 404, since routing happens client-side once the app has loaded);
- reverse-proxies `/api/*` to `runner-service`, including long-lived SSE connections (Caddy streams
  a chunked response as it arrives by default - no special buffering config needed, confirmed
  during the spike, see "Verified");
- will terminate TLS once a real domain exists (`SITE_ADDRESS` env var in the Caddyfile, unset
  today defaults to plain `:80`) - **D1 wired the rest of this**: `docker-compose.yml` passes
  `SITE_ADDRESS` through as a bare variable reference (deliberately not `${SITE_ADDRESS:-}` - see
  §5's own D1 task-list entry for why an empty-but-defined value is not the same as undefined and
  actually crashed Caddy when tried), publishes `80`/`443`, and Caddy has persistent named volumes
  for its own `/data` (ACME account + certificates) and `/config`. Still open: a real domain to
  actually exercise TLS issuance end to end (no domain purchased yet - D5's job) - everything above
  has only been verified with `SITE_ADDRESS` unset, i.e. plain `:80`;
- adds baseline security headers (`X-Content-Type-Options`, `X-Frame-Options`,
  `Referrer-Policy`, and strips the `Server` header).

This was already the intended design, not a new decision - `runner-dashboard/vite.config.ts`'s own
existing comment states the frontend only ever calls relative URLs specifically to match this
same-origin, Caddy-fronted deployment. No frontend code changes were needed to make this work.

`/actuator/*` and `/v3/api-docs` are **not** proxied wholesale. D4.3.1 added real Kubernetes-style
liveness/readiness probe groups (`management.endpoint.health.probes.enabled`, see
`runner-service/src/main/resources/application.yml`), so exactly three health paths are exposed
publicly (see `deploy/web/Caddyfile`): `/actuator/health` (the plain aggregate - now a genuinely
more honest signal too, since every custom indicator below also registers as a top-level
contributor by Spring Boot's own default convention), `/actuator/health/liveness` (JVM/process
alive only - `livenessState`, nothing else; deliberately never composed with anything that could
turn a self-resolving condition into a Docker restart-loop), and `/actuator/health/readiness`
(ready to accept a new run - `readinessState`, `db`, `recovery`, `disk`, `runnerAvailability`).
Every *other* `/actuator/*` path (`/actuator/env`, `/actuator/configprops`, a typo of one of the
three health paths above, ...) gets an explicit fail-closed `404` from Caddy itself, not the SPA's
`index.html` - see that file's own `@actuatorOther` matcher. `/actuator/prometheus` (D4.3.2) is the
one exception worth calling out explicitly: unauthenticated at the Spring Security layer, the same
posture as the three health paths (a real Prometheus scraper cannot perform an interactive GitHub
OAuth2 login), but - like every other non-health actuator path - still gets Caddy's own fail-closed
`404` externally; it is reachable only from inside the Compose network (no Prometheus/Grafana
container exists yet - deliberately deferred, D4.3.2's own scope) or via `docker-compose.debug.yml`'s
loopback-only override, never from outside. Both `show-details` and
`show-components` are `never` in `application.yml`, so an anonymous caller only ever sees a bare
`{"status": "..."}` on any of the three paths - never a contributor name, an exception message, or
a file-system path (verified for both the runner's permissive *and* real OAuth2-configured
security chains - `HealthEndpointAnonymousAccessTest` and
`OAuth2ChainAppliesAbuseRateLimitTest#probeSubPathIsReachableAnonymouslyOnTheOAuth2ChainToo`,
respectively). The `db`/`disk`/`recovery`/`runnerAvailability` -> `DOWN`/`OUT_OF_SERVICE` status
split is deliberate: a real Postgres outage (`db`) is the one failure class that genuinely needs
infra/operator action, so it reports `DOWN`; the other three are temporary, self-resolving
conditions (a low-disk warning, an in-progress D2.5 recovery scan, a `DEGRADED` runner waiting on
an unkillable process tree), so they report `OUT_OF_SERVICE` instead - never conflated, and
neither ever restarts the container by itself (`runner-service`'s Docker healthcheck targets
`/actuator/health/readiness`, but `restart: unless-stopped` only reacts to process *exit*, never to
health status alone - a real bootRun acceptance pass confirmed readiness reports `DOWN` within the
healthcheck's own 4-second bound during a real Postgres outage, and recovers back to `UP` on its
own once Postgres returns, with no `runner-service` restart involved). `web` (Caddy) does **not**
gate its own startup on `runner-service`'s health (`depends_on` stays a plain, unconditional
dependency, never `condition: service_healthy`) - the dashboard's own proven "backend unavailable"
UI and auto-recovery (`BackendUnavailableE2eTest`) must keep working even while the backend itself
is degraded or still recovering. The OpenAPI document is only ever needed against a real running
instance directly during dashboard development (`npm run api:check:contract`), never through the
public edge. The real `docker compose up` transition (`starting` -> `healthy`) and the Caddy
fail-closed matrix against a live Compose stack are acceptance-tested manually as part of D4.3.4's
consolidated pass, not automated here.

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

**Implemented in D1** (see `runner-service/src/main/java/.../catalog/RunAvailabilityPolicy.java`
and `.../config/RunAvailabilityConfig.java`), exactly as designed here:

```
RunCatalog                    - unchanged: every (Environment, Suite) combination this codebase
                                 knows how to run at all.
RunAvailabilityPolicy          - which of those combinations THIS deployment actually allows:
    - LOCAL_DEV profile (default): PUBLIC + LOCAL (today's behavior, unchanged)
    - PORTFOLIO profile: PUBLIC only
```

`RunRequestValidator.validate`/`.allowedCombinations` and `CapabilitiesResponse.current` both now
take a `RunAvailabilityPolicy` and filter through the *intersection* of `RunCatalog` and whichever
profile is active - `runner.deployment-profile` (env var `RUNNER_DEPLOYMENTPROFILE`, defaults to
`LOCAL_DEV`), set to `PORTFOLIO` in `deploy/docker-compose.yml`'s `runner-service` service - so the
dashboard's own launch form never even renders `LOCAL` as an option in the portfolio deployment -
not a client-side filter the frontend has to apply itself, the backend simply never advertises it.

**Verified live, both ends of the boundary**: with `RUNNER_DEPLOYMENTPROFILE=PORTFOLIO`, `GET
/api/v1/capabilities` returns `PUBLIC` only (no `LOCAL` entry at all), `POST /api/v1/runs` with
`{"environment":"LOCAL",...}` correctly `400`s, and a `PUBLIC`/`SMOKE` submission still queues and
runs normally. The unrestricted default (no env var set) still advertises both `PUBLIC` and `LOCAL`,
so existing local-development/CI behavior is unchanged.

Anonymous visitors can browse run history/results. Launch and cancel require authentication (D3).
Whether a rate-limited, anonymous `FIXTURE`-only demo mode is worth adding later is an open
question - the safe default for now is no anonymous launch capability at all.

## 3. PostgreSQL - schema and replay-atomicity protocol (design locked, reviewed 2026-09-06; not yet implemented)

Full implementation is D2's job. This section locks the schema shape and the transactional protocol
now so D2 has a settled target rather than a moving one. **Reviewed and corrected once already**
(2026-09-06, before any code was written) - four gaps found and folded in below: a missing fourth
table for `CUSTOM` test selections, a false assumption that the `runs`/`run_events` cutover could be
split into two independently-shippable steps, an artifact-ingestion design that would have silently
regressed the already-proven early-artifact-display feature, and no explicit rule for when the
service is actually allowed to accept traffic after a restart. See "D2 implementation plan" below
for the phase breakdown this review also settled.

### Tables

**`runs`** - replaces the in-memory `ConcurrentHashMap` `RunRepository` backs onto today:

| Column | Notes |
|---|---|
| `run_id` | primary key |
| `environment`, `suite` | |
| `status` | current `RunStatus` |
| `requested_at`, `started_at`, `finished_at` | nullable until reached |
| `exit_code`, `detail` | |
| `process_log_path` | relative to the artifacts/logs root, never a host-absolute path |
| `next_event_sequence` | the row-locked counter every event append allocates from (see below) |
| `version` | optimistic-concurrency column for concurrent lifecycle updates |
| `created_at`, `updated_at` | |

**`run_selected_tests`** - the fourth table this design's first pass missed: without it, a `CUSTOM`
run would survive a restart but silently lose its `List<SelectedTestSnapshot>` snapshot - exactly
the data `Run`'s own compact constructor was written to protect (see Faza D0.5's "snapshot never
mutates through lifecycle" invariant). One row per selected test, ordered:

| Column | Notes |
|---|---|
| `run_id`, `ordinal` | composite primary key - `ordinal` preserves selection order (the snapshot list's own order, not re-derivable from any other column) |
| `test_key`, `display_name`, `layer` | mirrors `SelectedTestSnapshot` exactly |
| *(unique)* `(run_id, test_key)` | mirrors `SelectedTestSnapshot`'s own no-duplicate-testKey invariant, enforced at the DB layer too, not just in Java |
| *(foreign key)* `run_id` → `runs(run_id)` `ON DELETE CASCADE` | |

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
| *(foreign key)* `run_id` → `runs(run_id)` `ON DELETE CASCADE` | |
| *(check)* `sequence > 0` | |

**`artifacts`** - metadata only, matching `ArtifactManifestEntry` today:

| Column | Notes |
|---|---|
| `artifact_id` | primary key |
| `run_id`, `test_id`, `step_id` (nullable) | |
| `artifact_type` | screenshot/trace/etc. |
| `relative_path`, `size_bytes`, `media_type` | `relative_path` relative to that run's own artifacts directory, never host-absolute |
| `created_at` | |
| *(foreign key)* `run_id` → `runs(run_id)` `ON DELETE CASCADE` | |
| *(check)* `size_bytes >= 0` | |

The actual screenshot/trace/video/log files themselves stay on the persistent volume
(`/data/runner-artifacts/...`) exactly as today - never inlined into Postgres. Indexes beyond the
primary keys above: `runs(requested_at DESC)` (backs `findAll`'s existing sort), `artifacts(run_id,
test_id, step_id)` (backs the dashboard's own group-by-`(testId, stepId)` grouping, see Faza B).

### Artifacts must ingest incrementally, not only at `RUN_FINISHED`

The dashboard
already shows a failing test's screenshot/trace before the whole run finishes (Faza A4/A4-review -
`RunDetailsPage`'s artifacts query invalidates on `TEST_FAILED`/`TEST_ABORTED`, not only on the
run's own terminal status). A `Run.submit`-to-`RUN_FINISHED` bulk-import of the manifest into
`artifacts` would silently regress that already-proven, already-tested behavior back to "wait for
the whole run." D2.4 must instead:

- Keep `ArtifactManifestWriter`'s JSONL manifest as the one raw, original piece of evidence -
  `artifacts` is a derived index over it, never the other way around.
- Read new manifest entries and `INSERT` them into `artifacts` at the same two points the frontend
  already relies on for early visibility: after every `TEST_FAILED`/`TEST_ABORTED` event (a step or
  test failure's manifest entry is guaranteed already written by then, see `TestFixture.captureFailure`'s
  own ordering), and once more as a final drain before `RUN_FINISHED` is recorded (covers artifacts
  from a test that failed without a screenshot capture, or any entry the last incremental pass
  hadn't yet seen).
- Make the insert idempotent by `artifact_id` (`ON CONFLICT (artifact_id) DO NOTHING` or an
  equivalent upsert) - the same manifest entry may legitimately be re-read by both an incremental
  pass and the final drain.
- Change nothing about the manifest's own existing defenses - path-traversal/symlink checks,
  strict-UTF-8 decoding, duplicate-`artifactId` detection (Faza A3) - `artifacts` ingestion sits
  downstream of all of them, never bypasses them.
- Define orphan behavior explicitly rather than leaving it implicit: a manifest entry with no
  corresponding file on disk (a crash between manifest-write and file-write) surfaces as a `404` at
  download time, the same as today; an `artifacts` row a later restart cannot associate with any
  file is a data-integrity signal to log, not a reason to fail an otherwise-successful ingestion
  pass.

### `runs`/`run_events` is one atomic cutover, not two independently-shippable steps

Splitting the migration into "move
`RunRepository` to Postgres first, move the event journal later" would create a real window where a
lifecycle status commits to Postgres while its corresponding `RUN_*` event still only exists in the
JSONL file (or vice versa) - exactly the "never a status update that commits with no matching
event" invariant below, broken by the migration's own sequencing rather than by a bug in either
side individually. **These two therefore ship as one atomic production cutover** (`runs` and
`run_events` flip together, even though the JDBC mapping code for each is written and reviewed
as two separate, smaller changes first - see D2.2/D2.3 in the implementation plan below). The
existing `RunRepository.save`/`transitionIfNonTerminal` `beforeCommit` callback shape is the right
*hook point* for this (a canonical event must be persisted before its corresponding REST-visible
state is committed - already true today, just against a JSONL append instead of a DB transaction),
but must not be carried over as a purely mechanical JDBC swap: the real replacement is one component
that owns the entire sequence below under a single per-run lock, not two components each doing half
of it.

### Replay-atomicity protocol

The exact risk this locks down: a live SSE subscriber must never observe a gap between "here is
everything that already happened" (the replay) and "here is everything from now on" (the live
feed) - either an event gets delivered twice, or (worse) silently dropped, if replay and live
registration aren't strictly ordered against concurrent appends. Single-instance architecture only
(the existing per-run in-process lock, not a distributed one - a multi-instance broker is
explicitly out of scope for this phase):

```
Append (one event being recorded, e.g. a lifecycle transition + its RUN_* event together):
  per-run lock
    database transaction
      → SELECT runs row FOR UPDATE            (also the row this transition validates against)
      → validate the requested status transition (RunStateMachine, unchanged)
      → allocate `sequence` from runs.next_event_sequence, still under that same row lock
      → INSERT into run_events
      → UPDATE runs (status/timestamps/next_event_sequence/version)
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

**Publish must happen after `COMMIT` succeeds but before the per-run lock is released - not before,
not after.** Two concrete failure modes this ordering exists to prevent, both flagged in review
before implementation:

- **Publish before commit**: if the transaction then rolled back (a constraint violation, a
  deadlock victim), a live subscriber would have already seen an event that officially never
  happened - the DB and the in-process Hub would disagree about history.
- **Publish after the lock is released** (even if strictly after a successful commit): a
  concurrent `Subscribe` could interleave *between* this append's commit and its own publish call -
  that subscriber's replay query (running after commit) would already include the new event, and
  then the late publish would deliver the same event to it a second time. Keeping publish inside
  the same lock section closes this window entirely: no `Subscribe` can run between this append's
  commit and its publish, because both are the same lock holder's own critical section.

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
  restart - it's already durable in `runs`/`run_events`/`run_selected_tests`/`artifacts`.
- Any run still `QUEUED`/`STARTING`/`RUNNING` at startup transitions to `ERROR` immediately, with a
  `detail` that says plainly the service restarted before the run finished, and exactly one
  corresponding `RUN_FINISHED(ERROR)` event inserted **in the same database transaction** as that
  status update - the ordinary lifecycle-transition invariant above applies to recovery too, not
  just to a live run. No attempt is ever made to reattach to or resume the old (by now certainly-
  gone) Gradle/Playwright process tree - the external OS process is unrecoverable once the JVM that
  was tracking it is gone, and pretending otherwise would be the actual footgun.
- History, completed events, `CUSTOM` selections, and artifacts for every other run stay fully
  readable and replayable immediately after restart - a fresh SSE subscriber to an old, already-
  terminal run just gets its full history from `run_events` and then (correctly) never sees a live
  event, since the run is terminal.
- **Recovery must complete before the service accepts any traffic - not run as a background task
  after startup.** Caught in review before implementation: if `POST /api/v1/runs` or the SSE
  endpoint could be reached before every stale non-terminal run has been recovered, a client could
  briefly observe a run still showing `RUNNING` from before the restart, or submit a new run while
  recovery is still rewriting old ones. Spring's embedded Tomcat can otherwise open its listening
  socket before an ordinary `@EventListener`/`CommandLineRunner`-style recovery hook has finished -
  this needs an explicit readiness gate (e.g. a `Filter`/handler interceptor, or a Spring Boot
  `ApplicationAvailability`-backed `readiness` state) that rejects `submit`/SSE-subscribe with a
  clear `503` until recovery has genuinely finished, not just an ordering assumption about when
  application startup callbacks happen to run relative to the HTTP listener binding.
- **Recovery is idempotent under a repeated/aborted-mid-recovery restart.** A run already recovered
  to `ERROR` on a previous startup is already terminal, so a second restart's recovery pass finds
  nothing left to do for it (the same "every terminal run is untouched" rule above, applied to a
  run recovery itself produced) - no double `RUN_FINISHED` event, no re-transition attempt.

### D2 implementation plan (locked, reviewed 2026-09-06)

**Technology choices** (deliberate, matching this project's own established preference for
explicit code over framework abstraction - see e.g. no generic `BasePage`, no Redux/Zustand on the
frontend): plain JDBC (`JdbcTemplate`/`NamedParameterJdbcTemplate`), not JPA/Hibernate - the
protocol above needs `SELECT ... FOR UPDATE`-style row locking and manual sequence allocation under
that same lock, both of which an ORM's own session/entity-state machinery fights against rather
than expresses cleanly. Flyway for migrations. **Testcontainers with a real `postgres` image for
every test that exercises this protocol - never H2**: H2 cannot faithfully emulate `jsonb` or
Postgres's own row-locking semantics, and this project's own established discipline (see
[[feedback_verify_root_cause_against_running_system]]) is to verify against the real system, not a
compatible-looking stand-in. A dedicated `databaseIntegrationTest` Gradle source set/task (mirrors
`dashboardE2eTest`'s own pattern of a separate source set for a heavier, real-infrastructure-backed
suite) keeps ordinary `runner-service:test` fast and Docker-free, while still giving CI a real,
separately-visible signal for what actually exercised the DB protocol - wired into the quality gate
as its own step, not folded silently into the default `test` task.

1. **D2.1 - PostgreSQL foundation** (schema and tooling only, zero behavior change): JDBC driver +
   Flyway + Testcontainers dependencies; Flyway migrations creating all four tables (`runs`,
   `run_selected_tests`, `run_events`, `artifacts`) with the foreign keys (`ON DELETE CASCADE`),
   check constraints (positive `sequence`, non-negative `size_bytes`, a valid `status` value), and
   indexes listed above; `databaseIntegrationTest` source set/task wired up; tests proving a fresh
   Testcontainers Postgres migrates cleanly, migrating twice is a no-op, and the constraints
   actually reject what they claim to (a negative `size_bytes`, a duplicate `(run_id, test_key)`, an
   orphaned `run_id` with no parent `runs` row).
2. **D2.2 - JDBC adapters, still no production cutover**: repository/store interfaces introduced;
   JDBC mapping of `Run` together with its `List<SelectedTestSnapshot>`; the row-locked sequence-
   allocation + event-insert logic, tested directly against a real Testcontainers Postgres for
   concurrency and rollback behavior. The existing in-memory `RunRepository`/file-backed journal
   stay the live, production-serving path throughout this phase - D2.2 is reviewed and merged as
   inert, not-yet-wired-in code.
3. **D2.3 - Atomic lifecycle + event cutover** (ships together with D2.2's code in one production
   switch, per the "one atomic cutover" section above - `RUN_QUEUED`/`RUN_STARTED`/`RUN_FINISHED`/
   `TEST_*`/`STEP_*` emission and SSE replay/`latest()` all flip at once). Required tests before this
   can be called done: a failed status update leaves no event; a failed event insert leaves no
   status change; a failed commit publishes nothing live; a successful commit whose live-publish step
   is then interrupted (simulated crash) is still recoverable via reconnect-and-replay; concurrent
   appends to the same run allocate a gapless `1..N` sequence; a subscribe racing a concurrent
   append never observes a gap or a duplicate; two concurrent attempts to finalize the same run
   produce exactly one `RUN_FINISHED`, never two, never zero. Only once every one of these is green
   does `FileBackedRunEventJournal` stop being the canonical source (the raw per-listener JSONL
   stays exactly as it is - original evidence, not replaced).
4. **D2.4 - Artifact metadata**, per the "Artifacts must ingest incrementally" section above:
   incremental ingestion at `TEST_FAILED`/`TEST_ABORTED` plus a final pre-`RUN_FINISHED` drain,
   idempotent by `artifact_id`, early-display behavior reverified live (not just unit-tested) to
   still work, the existing filesystem-security validation untouched, and artifact list/download
   both reverified to work correctly for a run recovered from before a restart.
5. **D2.5 - Restart recovery**, per the restart-behavior rules above: non-terminal → `ERROR` with a
   same-transaction `RUN_FINISHED(ERROR)`, no process-reattachment attempt, the readiness gate
   blocking `submit`/SSE until recovery finishes, idempotent under a repeated restart, `CUSTOM`
   selections/history/events/artifacts all still readable through and after recovery.
6. **D2.6 - Production acceptance** (the real end-to-end proof this whole phase is for): a real
   `docker compose` restart of `runner-service` while a run is genuinely `RUNNING`, confirming it
   comes back as `ERROR`; SSE reconnect-and-replay returns the complete event history for that run;
   older, already-terminal runs and their artifact downloads still work unchanged; `postgres` still
   publishes no host port at all (the D0/D1 security-boundary invariant, unaffected by adding real
   data to it); no event gap or duplicate across the whole exercise. Re-run the RAM/disk measurement
   from §5 once this phase is live - it is the first time this deployment carries any real Postgres
   read/write load, not just an idle container.

**D2.1 - DONE.** Flyway migrations create all four tables with the foreign keys, CHECK constraints
(the `runs` status set mirrors the lifecycle machine, matched with a generic `chk_runs_` prefix
assertion rather than one specific constraint name, since PostgreSQL doesn't guarantee which of
several simultaneously-violated CHECK constraints it reports first), and indexes described above.
`databaseIntegrationTest` is a dedicated Gradle source set/task (its own parallel CI job) proving a
fresh Testcontainers Postgres migrates cleanly, migrating twice is a no-op, and every constraint
rejects what it claims to. 26/26 green.

**D2.2 - DONE.** `JdbcRunStore` (`repository/jdbc` package) is the single component the design above
calls for, not a mechanical two-collaborator port of the old `RunRepository`/`RunEventAppender`
split: one method, one transaction - `SELECT ... FOR UPDATE` on the `runs` row, sequence allocated
from that same locked row, the event inserted, the run row updated, then committed - reusing
`Run.transitionTo`/`RunStateMachine` unchanged for validation. `appendEventIfNonTerminal(runId,
eventFactory)` is the separate append-only path for `TEST_*`/`STEP_*` events: it allocates the next
sequence and inserts the event under the same row lock without touching status/timestamps/`version`,
and rejects any `RUN_*` event type outright (that's `queue()`/`transitionIfNonTerminal`'s job).
Every write method returns a `CommittedRunChange(Run run, RunnerEvent event)` (event nullable for a
no-event transition).

Validation is unconditional and runs before any SQL executes, rolling back the whole transaction on
a violation: `requireMatchingEvent` (the returned event's `runId`/`sequence` must match what was
allocated), `requireLifecycleEventMatches` (`STARTING` requires no event; `RUNNING` requires a
`RUN_STARTED`; any terminal status requires a `RUN_FINISHED` with the matching `runOutcome`; `QUEUED`
is rejected outright, `queue()` has its own dedicated check), `requireSameIdentity` (a transition
function may only change status/timing/result, never `runId`/`environment`/`suite`/`requestedAt`/
`selectedTests`), and `requireReachableTransition` (independently re-checks the transition through
`RunStateMachine`, and that an already-set `startedAt` never changes). `findAll()` batch-loads every
run's `run_selected_tests` in one grouped query rather than one per run. Every timestamp is truncated
to microseconds (`Instant.truncatedTo(ChronoUnit.MICROS)`) before it enters this class, matching
`TIMESTAMPTZ`'s own storage precision.

Not a Spring bean and not yet wired to any live subscriber hub - both are D2.3's job.
`JdbcRunStoreTest` (22 cases) proves the round trip, concurrent-transition races (exactly one of two
racing finalize attempts applies, exactly one `RUN_FINISHED` is inserted), and that a conflicting
event insert rolls back the entire transaction including the status change.

**D2.3 - business-logic reconciliation DONE 2026-09-06; live wiring still open.** The reconciliation
this phase's own design called for is complete: `RunLifecycleStore` (new interface, `JdbcRunStore`'s
one production implementation) replaces `RunRepository`/`RunEventAppender` as what `RunEventBroker`
and `RunLifecycleCoordinator` depend on. `RunEventBroker`'s own per-run lock - unchanged from before
this cutover, it turns out already the exact external in-process lock the design called for - now
wraps `RunLifecycleStore.queue`/`transitionIfNonTerminal`/`appendEventIfNonTerminal` calls the same
way it always wrapped the old file-journal calls, publishing each committed event to the live `Hub`
before releasing that lock; `replayAndSubscribe` now reads replay history from
`RunLifecycleStore.readEventsAfter`/`latestEvent` instead of the retired `FileBackedRunEventJournal`.
`RunLifecycleCoordinator` lost its pre-cutover "emergency ERROR" fallback (a dedicated
`journalFailure` flag for the case where an in-memory repository write succeeded but a *separate*
file-journal write then failed) - deliberately, not an oversight: one atomic store transaction means
that split-brain state cannot occur any more, so the only fallback left anywhere in this path is
`RunService.executeRun`'s own pre-existing top-level catch block, unchanged. `RunRepository`/
`FileBackedRunEventJournal` and both their dedicated test files are deleted - fully unused the
moment nothing depended on them any more, not left as dead code.

`RunEventBrokerTest`/`RunServiceTest`/`RunLifecycleCoordinatorTest` all run against a new
`FakeRunLifecycleStore` - a behaviorally-faithful in-memory double sharing the exact same
`RunEventValidation` checks `JdbcRunStore` itself uses, so it can never silently accept or reject
something the real store wouldn't. `RunLifecycleCoordinator`'s pre-cutover "emergency ERROR" fallback
(for the case where an in-memory repository write succeeded but a separate file-journal write then
failed) is gone: one atomic store transaction makes that split-brain state impossible now, so the
only fallback left in this path is `RunService.executeRun`'s pre-existing top-level catch block.
`RunRepository`/`FileBackedRunEventJournal` are deleted.

Validation is hardened beyond D2.2's per-write checks: `RunEventValidation.requireLifecycleEventMatches`
runs unconditionally for every `RunStatus` (not just when a caller happens to pass an event factory);
`requireSameIdentity` rejects a transition function that changes any identity field
(`runId`/`environment`/`suite`/`requestedAt`/`selectedTests`); `requireReachableTransition`
independently re-validates the transition through `RunStateMachine` and requires an already-set
`startedAt` to never change. The per-run lock (`RunEventBroker` and `FakeRunLifecycleStore` alike) is
now backed by a fixed 256-entry `RunLockStripes` array indexed by `runId.hashCode()`, bounding memory
instead of growing one lock entry per run forever - at the cost of occasional, harmless false-positive
serialization between unrelated runs sharing a stripe.

**Real Spring wiring, Compose, and `dashboardE2eTest` Postgres are all done:**

- **Real Spring wiring.** `RunnerServiceApplication` no longer excludes `DataSourceAutoConfiguration`/
  `FlywayAutoConfiguration`. `JdbcRunStore` is a real `@Component` - `JdbcTemplate`,
  `TransactionTemplate`, and `ObjectMapper` all come from Spring Boot's own autoconfiguration, no
  manual `@Bean` wiring needed.
- **`application.yml`** carries local-`bootRun` `spring.datasource.*` defaults
  (`jdbc:postgresql://localhost:5433/runner`, `runner`/`runner`) - port 5433 rather than Postgres's
  usual 5432, to avoid colliding with an unrelated local Postgres service some dev machines already
  run on 5432. `runner-service/build.gradle`'s `localPostgresUp`/`localPostgresDown` tasks start/stop
  a throwaway `postgres:17-alpine` container for local development via a plain `docker run`/`docker
  rm -f` pair, polling `pg_isready` with no fixed blind delay.
- **`deploy/docker-compose.yml`** - `runner-service` depends on `postgres` with
  `condition: service_healthy` (not just container-started, avoiding a race against Postgres still
  initializing), and carries `SPRING_DATASOURCE_URL`/`_USERNAME`/`_PASSWORD` env vars reusing the
  same `POSTGRES_DB`/`POSTGRES_USER`/`POSTGRES_PASSWORD` values the `postgres` service itself uses.
- **`dashboardE2eTest`** - `DashboardE2eDatabase` owns one Testcontainers `postgres:17-alpine`
  container, lazily started and shared for the whole suite's JVM; Flyway migrates on the launched
  jar's own startup. Stopped from `DashboardE2eEnvironment`'s root-context close, with Testcontainers'
  own Ryuk reaper as the fallback for a filtered run.
- **`OpenApiContractTest`/`ServerBindingTest`** (the two Docker-free, full-`@SpringBootTest`-context
  tests) re-exclude `DataSourceAutoConfiguration`/`FlywayAutoConfiguration` at the test level,
  alongside a `@MockitoBean RunLifecycleStore` - the ordinary `test` task still never needs a real
  Postgres.
- **Acceptance matrix** - `RunEventBrokerJdbcAcceptanceTest` (`databaseIntegrationTest`) proves,
  against a real `RunEventBroker` wrapping the real `JdbcRunStore` and a real Testcontainers Postgres:
  concurrent appends under real subscriber traffic deliver every event exactly once, in order; a
  replay/subscribe race against a concurrent append serializes deterministically; and a run's
  complete history survives and is fully recovered via `replayAndSubscribe` alone when no subscriber
  was ever attached at all, including the specific window where the process dies between a commit and
  the in-process `hub.publish` call.

**D2.4 - Artifact metadata - DONE.** Per the "Artifacts must ingest incrementally" section above:

- **New `ArtifactRepository`** (interface) / **`JdbcArtifactRepository`** (real `@Component`,
  `JdbcTemplate`-backed): `ingest(entries)` batch-inserts with `ON CONFLICT (artifact_id) DO NOTHING`
  (idempotent by design - the same manifest entry may legitimately be re-read by both an incremental
  pass and the final drain); `findForRun(runId, testIdFilter)` queries `artifacts` ordered by
  `created_at` then `artifact_id`.
- **New `ArtifactIngestionService`**: reads a run's `manifest.jsonl` (via the existing
  `ArtifactManifestReader`, unchanged - same path-traversal/symlink defenses, same strict-UTF-8
  decoding, same duplicate-`artifactId` detection) and ingests every entry into `ArtifactRepository`.
  Always re-reads the whole manifest rather than tracking a byte/line offset, relying entirely on the
  repository's own idempotency. **Never throws** - a read/parse/database failure is logged and
  swallowed, since `artifacts` is a derived index, not a source of truth, and losing one pass is
  always recoverable from the next one.
- **Incremental hook**: `RunEventBroker#append` calls `ingestAvailableEntries(runId, false)` after
  every `TEST_FAILED`/`TEST_ABORTED` event, *after* releasing the per-run lock (this ingestion shares
  no invariant with the replay-atomicity protocol that lock protects, so holding it any longer would
  only needlessly block a concurrent `replayAndSubscribe`/`append` on the same run).
- **Final-drain hook**: `RunLifecycleCoordinator#finishIfLive` calls
  `ingestAvailableEntries(runId, true)` immediately *before* the terminal transition/`RUN_FINISHED`
  commit - covers a test that failed without a screenshot capture, or any entry the last incremental
  pass hadn't yet seen.
- **`ArtifactService` rewritten**: `listForRun`/`download` now query `ArtifactRepository` exclusively
  - the manifest file itself is no longer read here at all (that's `ArtifactIngestionService`'s own,
  separately-tested job). `resolveFile`'s filesystem trust boundary (path-traversal/symlink checks) is
  completely unchanged - an `ArtifactManifestEntry` read back from Postgres still originated from the
  manifest file, so its `relativePath` is treated exactly as untrusted as it always was.
- **Test rewiring**: `ArtifactServiceTest` rewritten against a new `FakeArtifactRepository` (in-memory,
  mirrors the real repository's `ON CONFLICT DO NOTHING` semantics via `putIfAbsent`) instead of
  writing manifest files for its list tests - the download/symlink/corrupt-file tests still write real
  files to disk (that trust boundary is unchanged) but seed the fake repository instead of a manifest
  line. New `ArtifactIngestionServiceTest` (4 cases) directly proves the ingest/idempotent/never-throws
  contract. `RunEventBrokerTest`/`RunLifecycleCoordinatorTest`/`RunServiceTest`/
  `RunEventBrokerJdbcAcceptanceTest` each gained a `noopArtifactIngestionService()` helper (a real
  `ArtifactIngestionService` wired to `FakeArtifactRepository`) to satisfy `RunEventBroker`'s/
  `RunLifecycleCoordinator`'s new constructor parameter - none of those classes' own scenarios ever
  produce a `TEST_FAILED`/`TEST_ABORTED`/`RUN_FINISHED` manifest, so the hook never does anything
  observable in them. `OpenApiContractTest`/`ServerBindingTest` gained a `@MockitoBean
  ArtifactRepository` for the same reason `RunLifecycleStore` is already mocked there:
  `JdbcArtifactRepository` also needs a `JdbcTemplate`, which these two Docker-free, real-Postgres-free
  full-context tests deliberately exclude.
- **New `JdbcArtifactRepositoryTest`** (`databaseIntegrationTest`, 5 cases) proves the real
  implementation against a real Postgres: an entry round-trips through the table exactly, a re-ingest
  with different field values for the same `artifactId` never overwrites the original row,
  `findForRun` filters by `testId` and orders deterministically. Caught a real bug in the test itself
  while writing it (not in production code): `artifact_id` is a *global* primary key, not scoped per
  run, and every test method in the class shares one static Testcontainers Postgres/schema - reusing a
  bare literal like `"a"` across two test methods silently collided with a leftover row from an
  earlier, unrelated test via the very `ON CONFLICT DO NOTHING` being tested, producing an empty result
  instead of a clear failure. Fixed with a `uniqueArtifactId(label)` helper appending a fresh UUID to
  every artifact id used in the test class.

Ingestion runs synchronously inside the same per-run lock the replay-atomicity protocol uses, before
`hub.publish` - guaranteeing a `TEST_FAILED`/`TEST_ABORTED` event is never observable to an SSE
subscriber before its artifacts are already durably ingested. A genuine `artifact_id` collision (the
same id arriving with a different `runId`, path, type, or size - a real data-integrity problem, as
opposed to a legitimate re-read of an identical entry) throws `ArtifactIngestionConflictException`
rather than being silently discarded by `ON CONFLICT DO NOTHING`. A failed final drain is tracked
explicitly, not just logged: a `runs.artifacts_ingestion_incomplete` column is set/cleared around the
terminal drain, a bounded background reconciliation loop retries a flagged run up to 5 times before
giving up permanently, and `GET .../artifacts` carries an `X-Artifacts-Ingestion-Incomplete` response
header so a client can tell "zero artifacts" apart from "ingestion never finished". `createdAt` is
truncated to microseconds before both the write and the conflict comparison, matching `TIMESTAMPTZ`'s
storage precision and `JdbcRunStore`'s own precedent.

**D2.5 - Restart recovery - DONE.** Per the "Restart behavior" section above:

- **`RunRecoveryService`** (`@Component implements ApplicationRunner`): on startup, loads every
  non-terminal `Run` via `RunLifecycleStore#findNonTerminal` and recovers each one to `ERROR` via
  `RunLifecycleCoordinator#finishIfLive` - the same one-transaction status+event commit every other
  terminal transition uses. No attempt is made to reattach to the run's old external process - it is
  presumed gone, per the architecture rule above.
- **Readiness gate, not a startup-ordering assumption**: `ApplicationRunner` runs after Tomcat has
  already opened its listening socket, so the socket itself can't be held closed during recovery.
  Instead, an `AtomicBoolean recoveryComplete` backs `requireRecoveryComplete()`, called by
  `RunService#submit`, `RunService#cancel`, and `RunEventStreamController#stream` before doing
  anything else, throwing `RunnerRecoveringException` (mapped to `503`). Read-only endpoints
  (`GET /runs`, `GET /runs/{id}`, log/artifact downloads) stay ungated.
- **Fail-closed, not fail-open, on a partial failure**: a per-run `try/catch` lets every run in the
  pass be attempted even after one fails, but `recoveryComplete` is only ever set once every run
  succeeded; a failure recovering any run, or loading the non-terminal set itself, throws out of
  `ApplicationRunner#run`, which fails the whole application's startup - `restart: unless-stopped`
  then retries the pass from scratch, and every run that already recovered stays durably `ERROR`, so
  the retry only has the genuinely-still-failing run(s) left. The aggregate exception carries every
  failed run's own cause as a suppressed exception.
- **Idempotent by construction**: a run already recovered to `ERROR` is already terminal, so a later
  restart's pass filters it out the same way any other terminal run is - never a second
  `RUN_FINISHED`.
- `findNonTerminal` is backed by a partial index (`V3`, keyed on `requested_at WHERE status IN (...)`)
  using a literal status list rather than bind parameters, since PostgreSQL's planner cannot reliably
  prove a parameterized condition implies a partial index's own predicate - `EXPLAIN` is asserted
  directly in `RunRecoveryServiceJdbcAcceptanceTest` to confirm the index is actually used, not just
  trusted by inspection.
- `RunRecoveryServiceJdbcAcceptanceTest` (`databaseIntegrationTest`) proves the fail-closed path
  against a real Postgres, including a genuine trigger-forced database-level failure during recovery
  (not just a mocked store exception).

**D2.6 - Production acceptance - DONE.** No code changes to `runner-service` itself -
this phase is entirely the real end-to-end proof D2.1-D2.5 were built for, against the actual
three-container production topology (`deploy/docker-compose.yml`), not `bootRun` + a bare local
Postgres. One real config change did land here: `web`'s published host ports are now
`${WEB_HTTP_BIND:-80}`/`${WEB_HTTPS_BIND:-443}` instead of hardcoded `80`/`443`, giving a local
acceptance run a durable way to remap the host side when something else on the dev machine already
holds 80/443. Production is unaffected: both env vars are unset in any real deployment, so
Compose's own `${VAR:-default}` falls back to the real `80`/`443` exactly as before.

**Reproducible commands** (run from the repository root; `deploy/.env` - gitignored - needs
`WEB_HTTP_BIND`/`WEB_HTTPS_BIND` set only if host 80/443 is already taken, see
`deploy/.env.example`):

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.debug.yml \
  --env-file deploy/.env up --build -d
# ... submit runs, poll for the crash window (see below) ...
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.debug.yml \
  --env-file deploy/.env kill -s SIGKILL runner-service
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.debug.yml \
  --env-file deploy/.env up -d runner-service
# ... verify recovery, reconnect-and-replay, artifacts (see below) ...
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.debug.yml \
  --env-file deploy/.env down -v
```

A full, sanitized transcript of every command and response below (`docker compose ps`, REST
responses, SSE output, the recovery log, `docker stats` samples, and the Postgres/artifact
consistency checks) is committed in [`docs/RELEASE_EVIDENCE.md`](RELEASE_EVIDENCE.md)'s own
"Faza D2.6" section, following that file's existing evidence-log convention rather than a new,
separate transcript file.

- All three containers come up; `postgres` publishes no host port at all, and `runner-service`
  migrates cleanly against it on first boot.
- The network-isolation invariant holds under real D2 traffic: `web` cannot reach `postgres:5432`
  directly (no membership on the `data` network), while `runner-service` reaches it for Flyway/JDBC.
- The crash scenario uses a genuine `SIGKILL`, not `docker compose restart` (which sends `SIGTERM`
  and lets Spring shut down gracefully - not the hard-crash recovery is meant to handle): a
  `SIGKILL`ed run is left mid-flight in Postgres (`status = RUNNING`, no partial/corrupt row), and
  restarting `runner-service` recovers it to `ERROR` with exactly one `RUN_FINISHED` event.
- Artifacts ingested before a crash (metadata durably written, but the run's own `RUN_FINISHED` not
  yet committed) survive recovery unchanged and remain downloadable byte-for-byte.
- Reconnect-and-replay is exact against a hard-killed run: a live subscriber sees events up through
  the last one committed before the kill and then drops with no `RUN_FINISHED`; a fresh subscription
  after recovery gets the complete history in order, and a subscription resuming with the dropped
  subscriber's own `Last-Event-ID` gets exactly the missed event - no gap, no duplicate.
- The idempotent-no-op path (an ordinary restart with nothing left non-terminal) and history for
  older, already-terminal runs both hold against the real stack too.
- The §5 RAM/disk measurement was re-run with both the D1 heap caps and D2's real Postgres
  persistence path active - see the "D2.6 remeasurement" note under §5.

## 4. Security boundary

D3.2 - admin authentication (GitHub OAuth2 Login), implemented and locally verified against a
real running backend. Backend-driven: Spring Security's OAuth2 Login talks to GitHub server-side;
the access token never reaches the React frontend, which only ever sees an `HttpOnly` session
cookie and the `GET /api/v1/auth/me` JSON response. The admin is allowlisted by GitHub's immutable
numeric account ID (`RUNNER_SECURITY_ADMIN_GITHUB_ID`), never by username - `GithubOAuth2UserService`
throws for any non-matching, missing, or malformed id, so a non-allowlisted GitHub account never
receives any session at all (there is no intermediate "authenticated but not admin" state).

The `PORTFOLIO` deployment profile fails closed **before any listening socket is ever opened**:
`RunnerSecurityEnvironmentPostProcessor` checks this during environment preparation (ordered right
after `ConfigDataEnvironmentPostProcessor`), which runs before `SpringApplication` even creates the
`ApplicationContext` - not an `ApplicationRunner`, which only executes after the context has fully
refreshed and the embedded Tomcat is already listening, which would briefly serve real anonymous
traffic through the permissive chain on every restart of a misconfigured instance before getting a
chance to throw. A malformed `RUNNER_SECURITY_ADMIN_GITHUB_ID` fails even earlier still,
at `AdminGithubAllowlist`'s own `@Bean` construction. `RunnerSecurityFailFastTest` proves this by
binding a plain `ServerSocket` to the exact port a misconfigured instance was told to use,
immediately after the expected startup exception - the bind succeeds, confirming the port was
never touched.

This check binds `runner.deployment-profile` via Spring Boot's own `Binder` API, not a raw string
comparison - comparing the literal `"PORTFOLIO"` string could disagree
with `RunAvailabilityConfig`'s own `@Value`-based enum conversion (case-insensitive: a lowercase
`portfolio` would silently bypass this check while still resolving to `PORTFOLIO` later), so a
security decision must never depend on whether an unrelated, later config binding happens to
agree with it. The same pass additionally requires a `Secure` session cookie under `PORTFOLIO`
(catching a local Compose acceptance override left in place by mistake - see the
`SESSION_COOKIE_SECURE` escape hatch below), failing closed identically to missing OAuth2
credentials.

The frontend never gates its Run/Cancel controls on "is someone logged in" - `GET /api/v1/auth/me`
reports a `canManageRuns` boolean instead, which is `true` in the permissive chain (no GitHub
OAuth2 configured - default local `bootRun`, `dashboardE2eTest`) regardless of authentication,
exactly like this project's whole pre-D3.2 history; only once OAuth2 is genuinely enabled does
`canManageRuns` require being the allowlisted admin. A separate `authenticationRequired` boolean
hides the login control entirely when there is no login concept to offer, so "authenticated" and
"can manage runs" never get conflated into a permissive-chain deployment that reads as silently
read-only. `CurrentUserController` derives `canManageRuns` from the real `ROLE_ADMIN` authority
Spring Security itself grants, never merely from the principal being an `OAuth2User` - the endpoint's
own contract describes the actual authorization rule rather than relying on
`GithubOAuth2UserService` never producing a non-admin session.

The frontend also never enables a Run/Cancel control on `canManageRuns` alone: `useCanManageRuns()`
additionally requires the CSRF token to be primed (`useCsrfReady`, an error-only-retry query
mirroring the capabilities-retry idiom already used elsewhere) - an admin whose CSRF priming is
still failing (backend briefly unreachable at bootstrap) would otherwise see an enabled control
that only fails downstream with a 403 for a missing header. Logging out invalidates the shared
CSRF query so every mounted consumer re-primes for the new anonymous session.

Authentication endpoints live under `/api/v1/auth/**` (a URL namespace, never a single grouped
`permitAll`/`denyAll` matcher - see `SecurityConfig`'s default-deny `authorizeHttpRequests` list,
which enumerates every route explicitly). CSRF stays enabled for the whole authenticated chain
(`GET /api/v1/auth/csrf` primes/refreshes the `XSRF-TOKEN` cookie, echoed back via
`X-XSRF-TOKEN`); the session cookie is `HttpOnly`, `SameSite=Lax` (an OAuth callback is a
top-level cross-site GET navigation that `Strict` would break), and `Secure` in the real deployment
only (`SERVER_SERVLET_SESSION_COOKIE_SECURE=true` in `deploy/runner-service/Dockerfile` - local
`http://127.0.0.1` dev cannot use a `Secure` cookie at all). `deploy/docker-compose.yml` exposes a
`SESSION_COOKIE_SECURE` escape hatch (same shape as `WEB_HTTP_BIND`/`WEB_HTTPS_BIND`, defaults to
`true`) for a Compose-based local acceptance run reached over plain HTTP with no domain/TLS
configured yet - browsers refuse to send a `Secure` cookie to a non-HTTPS origin, so login could
otherwise never persist a session there. This does not weaken `PORTFOLIO`'s own fail-closed
guarantee: the environment post-processor above still refuses to start if `PORTFOLIO` is ever
combined with a non-Secure cookie, so this override is only usable together with a non-`PORTFOLIO`
`RUNNER_DEPLOYMENTPROFILE` for that same local run. The same Dockerfile also sets
`SERVER_FORWARD_HEADERS_STRATEGY=native` (D3.2 originally set `framework`; changed during D3.3's
abuse-protection work - see below for why) - without trusting forwarded headers at all, Spring
Security's OAuth2 `{baseUrl}` resolution would see the internal plain-HTTP hop between Caddy's TLS
termination and this service, producing a `redirect_uri` that never matches the `https://` one
registered with the GitHub OAuth App; safe only because Caddy is the sole edge that can ever reach
`runner-service` (no published port) and is therefore the only entity able to set `X-Forwarded-*`
headers here. 401/403 responses from the security layer carry the same `ProblemDetail` shape
(`title`/`status`/`detail`/`instance`) every other API error already uses, via the application's
own managed `ObjectMapper`.

### D3.3 - abuse protection

A per-resource-surface rate-limit matrix, not one global limit (`AbuseRateLimitFilter` +
`InMemoryRateLimiter`, a small dependency-free fixed-window limiter matching this project's
existing capacity-ceiling style): OAuth authorization 5/min and OAuth callback 10/min (both keyed
by client IP - the callback specifically is the one login-flow request that spends a real GitHub
API call), create-run 3/min **and** 10/hour and cancel-run 10/min (both keyed by the caller's
GitHub numeric id), public REST GET 120/min and log/artifact download 30/min (both keyed by
client IP). A violation returns `429` with the same `ProblemDetail` contract as every other
security response, plus a real `Retry-After` header. `runner.queue-capacity` (existing) is
explicitly not a rate limit - it bounds queued runs, never call frequency.

Anonymous, unauthenticated surfaces are the largest attack surface here - SSE connections
specifically get their own per-client-IP concurrent-connection cap (`SseConnectionsPerIpTracker`, 3
by default), enforced *alongside* the existing global `sseMaxSubscribers` ceiling: without it, one
client alone could occupy every global slot.

**Client-IP keys are only trustworthy because of a verified reverse-proxy trust boundary** - never
trust `X-Forwarded-For` blindly, even behind a reverse proxy this project controls. Read directly
from Spring Boot 4's own source
(`TomcatWebServerFactoryCustomizer.customizeRemoteIpValve`): `SERVER_FORWARD_HEADERS_STRATEGY=native`
activates Tomcat's own `RemoteIpValve` (`server.tomcat.remoteip.internal-proxies`, whose default
already covers every private/Docker-internal range), which only honors `X-Forwarded-For` when the
*direct* TCP peer matches that trusted range - `runner-service` has no published port and shares
its `edge` network with exactly one other container (Caddy), so in this topology only Caddy can
ever be that peer. The prior `framework` strategy's plain `ForwardedHeaderFilter` has no such
concept at all and would parse the header unconditionally regardless of who sent it. `RemoteIpValve`
is a Tomcat connector-level `Valve`, so `MockMvc` cannot exercise it directly (it dispatches
straight to `DispatcherServlet`, never booting a real embedded Tomcat) - real verification of this
mechanism happens only against a genuine Compose stack with a real Caddy in front, not simulated
locally; see `docs/RELEASE_EVIDENCE.md`'s D3.3 section for what was and wasn't verified this way.

`AbuseRateLimitFilter` is registered after `SecurityContextHolderFilter`, not after
`AuthorizationFilter`: the OAuth-authorization/callback routes are handled and their response fully
committed by Spring Security's own `OAuth2AuthorizationRequestRedirectFilter`/
`OAuth2LoginAuthenticationFilter` before `AuthorizationFilter` ever runs, so registering later would
silently never rate-limit them. `SecurityContextHolderFilter` is early enough to still catch those
routes while late enough that an already-authenticated admin's session `Authentication` is available
for the numeric-id key extraction on the create/cancel-run surfaces.

The request body itself is capped before any JSON deserialization is even attempted
(`RequestBodySizeLimitFilter`, registered in both chains - a resource-protection concern, not
auth-adjacent, unlike the rate limiter above): a `Content-Length` over `runner.max-request-body-bytes`
(16 KiB default) is rejected immediately with `413`, and the request `InputStream` is additionally
wrapped in a byte-counting stream that aborts even against a lying or chunked request. `CreateRunRequest`
also carries `@Size(max = 25)` on `testKeys` and `@Size(max = 200)` per key - Bean Validation as an
independent second layer, not a replacement for `CustomTestSelectionValidator`'s own identical
25-key cap against the live test catalog. Two things confirmed empirically rather than assumed: a
`@Valid` failure already produces a compliant `400` with zero extra code, and Spring Boot's Jackson
autoconfiguration defaults to *lenient* deserialization (an unrecognized JSON field is silently
ignored) - the opposite of what was first assumed, so `spring.jackson.deserialization.fail-on-unknown-properties: true`
was added explicitly to make the request schema strict.

`Content-Security-Policy` and `Permissions-Policy` were added to `deploy/web/Caddyfile`'s existing
header block, not to Spring Security - Caddy is the only thing that ever serves the SPA's own
`index.html`/JS/CSS responses directly (`file_server`; `runner-service` is only ever proxied for
`/api/*`/`/actuator/health`), so a Spring-Security-only CSP would never reach the page that needs
it most; Spring Security also deliberately never sets one of its own. The directive set was
verified against the real production dashboard bundle (`npm run build` + reading the built
`index.html`/CSS: no inline scripts/styles, no `data:` URIs anywhere), so neither `'unsafe-inline'`
nor `data:` was added preemptively - the one legitimate cross-origin resource is the logged-in
admin's own GitHub avatar image. Both headers, and the config as a whole, were confirmed against a
real running `caddy:2-alpine` container (the stock image this project already uses, no custom
`xcaddy` build needed), not just syntax-validated.

CORS stays fully unsupported: no `CorsConfigurationSource` bean anywhere, `.cors(CorsConfigurer::disable)`
explicit on both `SecurityFilterChain` beans - turning what was already the correct behavior
(Spring Security never adds `Access-Control-Allow-Origin` without an explicit CORS configuration)
into a stated, tested decision rather than one that was merely never wrong by omission.

Both `AbuseRateLimitFilter` and the body-size filter are also registered as disabled
`FilterRegistrationBean`s in `SecurityConfig`, so Spring Boot's automatic servlet-container
registration of any plain `Filter` bean never runs either one a second time outside its explicit
`SecurityFilterChain` wiring (`PermissiveChainHasNoAbuseRateLimitTest`/
`OAuth2ChainAppliesAbuseRateLimitTest` prove this against a real embedded Tomcat, since the
auto-registration behavior only exists there, not under `MockMvc`). `InMemoryRateLimiter`'s per-key
map sweeps expired windows periodically and enforces a hard maximum tracked-key count with
oldest-entry eviction, bounding memory against a flood of genuinely distinct client IPs. Multi-rule
checks (create-run's per-minute *and* per-hour limits) are one atomic check-then-commit operation, so
a request rejected by one rule never silently consumes another rule's budget, and `Retry-After`
rounds up rather than truncating. The body-size filter buffers and validates the entire request
before invoking the rest of the filter chain, guaranteeing a real `413` even against a chunked or
falsified `Content-Length`.

| Surface | Access |
|---|---|
| Run history, results, artifacts (read) | Public, anonymous - rate-limited 120/min (reads) or 30/min (log/artifact downloads) per client IP |
| Live SSE event stream | Public, anonymous - capped at 3 concurrent connections per client IP, on top of the existing global `sseMaxSubscribers` |
| GitHub OAuth login (authorization/callback) | Public - rate-limited 5/min (authorization) and 10/min (callback) per client IP |
| Launch a run, cancel a run | Requires `ROLE_ADMIN` (GitHub OAuth2 Login, numeric-ID allowlist) and a valid CSRF token - additionally rate-limited (3/min and 10/hour for create, 10/min for cancel) per admin GitHub numeric ID |
| `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | Public at both the Spring Security layer and the production Caddy edge. D4.3.1's real Kubernetes-style probe groups: root `/actuator/health` is now an aggregate that folds in DB/disk/recovery/runner-availability (no longer liveness-only - every custom indicator also registers as a top-level contributor by Spring Boot's own default convention); `/liveness` stays `livenessState`-only (JVM/process alive, never gated on Postgres/disk/recovery, so a condition a restart can't fix never restart-loops the container); `/readiness` composes `readinessState`, `db`, `recovery`, `disk`, `runnerAvailability`. `show-details`/`show-components` are both `never`, so every anonymous response is a bare `{"status": "..."}` on all three paths, on both the permissive and OAuth2-configured chains alike |
| `/actuator/info` | Public at the Spring Security layer, but the production Caddy edge continues to expose only the three health paths above - `/actuator/info` remains unreachable publicly (a deliberate two-layer distinction: app-level permission vs. edge-level exposure, not an inconsistency) |
| `/v3/api-docs` | Public at the Spring Security layer (so `npm run api:export`/`api:check:contract` keep working against a local `bootRun` once OAuth2 is enabled) - never proxied publicly by Caddy either way |
| Any other actuator endpoint (`/actuator/prometheus`, `/actuator/env`, `/actuator/configprops`, a typo of one of the three health paths, ...) | Not proxied publicly at all - Caddy responds `404` itself (see `deploy/web/Caddyfile`'s `@actuatorOther` matcher), never the SPA's `index.html` |
| PostgreSQL | No published port anywhere, on any network - reachable only from `runner-service`, the only service attached to both `edge` and `data`; `web` has no membership on `data` at all, so it cannot reach `postgres` regardless of `internal: true` (see §1's corrected explanation of what that flag does and does not do) |
| `runner-service` itself | No published port in the base Compose file at all - only reachable through `web`. `docker-compose.debug.yml` publishes a loopback-only debug port for local measurement/troubleshooting; never applied in a real deployment |
| Docker socket | Never mounted into any container - nothing here starts/stops/manages other containers or the host |

### D3.4 - security test coverage

An audit of what D3.1-D3.3 already covered incidentally, closing the real gaps rather than
re-testing what already had coverage. Session cookie **idle** timeout shortened from `12h` to `4h`
(`server.servlet.session.timeout`) - a rarely-used single-admin session doesn't need a long idle
window. This is an *idle* timeout under the Servlet `HttpSession` contract (the clock resets on
every access), not an absolute session lifetime - an actively-used session is never force-expired at
the 4h mark regardless of how long it has existed. Judged a reasonable "forgot to log out" bound for
this single-admin, `HttpOnly`+`Secure`-cookie deployment, not a defense against an already-stolen
active session (a deliberate scope decision - see `docs/RELEASE_EVIDENCE.md`'s D3.4 section). New tests close
five concrete gaps: a mismatched (not merely missing) CSRF token still gets `403`; an invalidated
session is provably treated as fully anonymous on its very next use (never a lingering admin
session, never a `500`) - proving only the post-invalidation state, not the idle-timeout clock
itself, which `MockMvc` cannot simulate elapsing; `MUTATION` is structurally unreachable (not even a
`Suite` enum value) and `FIXTURE` gets exactly the same admin-only authorization treatment as any
other mutating suite, both proven rather than assumed from reading the enum.

The real browser E2E login->launch->cancel->logout, deliberately left manual-only at D3.2 to avoid
a real-GitHub-account dependency in CI, is now automated without reintroducing that risk: a new,
fully isolated `OAuthFlowE2eTest` runs the real `runner-service` with its normal Spring Boot OAuth2
client binding, redirecting only the three GitHub endpoint URIs
(`spring.security.oauth2.client.provider.github.*`) at a local WireMock stub - never a fake
in-test `ClientRegistrationRepository` - so the real environment-postprocessor credential bridging,
the real `ClientRegistrationRepository`, and the real `GithubOAuth2UserService` are all genuinely
exercised. Two scenarios: the full happy path (login through a stubbed GitHub round trip, launch
and cancel a run, logout, confirm the session and its controls both revert to anonymous), and a
non-allowlisted GitHub identity being rejected outright with no admin session at all. See
`docs/RELEASE_EVIDENCE.md`'s D3.4 section for the full test list and verification transcript.

## 5. Resource measurement and VPS sizing

Measured with `docker stats` against the real three-container stack above
(`deploy/docker-compose.yml` + the local-only `deploy/docker-compose.debug.yml` override for port
access - see "Verified"). Sampling `PUBLIC`/`JOURNEY` every 5s understates the real number: `Suite.JOURNEY`
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

- **Done**: explicit `-Xmx` on `runner-service`'s own launch (`JAVA_TOOL_OPTIONS`, wired in the
  Dockerfile at `256m`) **and** a `maxHeapSize = '512m'` now applied to every `Test` task in the
  root `build.gradle` (`tasks.withType(Test).configureEach`, covers `test`/`smokeTest`/.../
  `customTest`/`localTest`/`localJourneyTest`/`dashboardE2eTest` alike, current and future, with
  nothing to remember to repeat per task) - this is the real per-run child JVM `RunService` launches
  once per dashboard run, so this directly bounds that footprint.
- **Done**: `docker-compose.yml` passes `SITE_ADDRESS` through to `web` as a **bare** variable
  reference (`environment: [SITE_ADDRESS]`, no `${SITE_ADDRESS:-}` default expression -
  `deploy/.env.example` documents leaving it commented out). This distinction is load-bearing, not
  stylistic: Compose only *omits* the container's env var entirely when the variable is genuinely
  undefined; `${SITE_ADDRESS:-}` instead always sets it to an empty string when unset, which Caddy's
  own `{$SITE_ADDRESS::80}` placeholder does **not** treat the same as undefined - an empty site
  address parses as a bare global-options block instead of this file's actual site block. This
  exact bug was found and fixed live during D1's own Docker verification round (Caddy failed to
  start: `unrecognized global option: encode`) - see the "Round 3 (D1 implementation)" entry under
  "Verified" below for the full repro and fix.
- **Done**: `web` publishes `80`/`443` (and `443/udp` for HTTP/3) directly in the base
  `docker-compose.yml` - `docker-compose.debug.yml` no longer duplicates a `web` port mapping, it
  now only adds the loopback-only `runner-service` debug port.
- **Done**: named volumes `caddy-data:/data` and `caddy-config:/config` on `web`.
- **Done** (D2.6, below): re-ran the RAM/disk measurement with heap caps and D2's real Postgres
  persistence path both active. Still open: a real domain to actually prove TLS issuance end to end
  (no domain purchased yet - D5's job); everything above has only been verified with `SITE_ADDRESS`
  unset (plain `:80`), not against a real ACME challenge.

### D2.6 remeasurement (2026-09-06) - heap caps and real Postgres persistence both active

The measurement above predates both the `-Xmx256m`/`maxHeapSize=512m` heap caps and D2's real
Postgres read/write path - re-measured against the same real three-container stack, same
`PUBLIC`/`REGRESSION` suite, same `docker stats` sampling methodology, now that both are genuinely
in place:

| Container | Idle | Peak during an active `REGRESSION` run |
|---|---|---|
| `runner-service` | ~380 MB (up from ~215 MB - Hikari connection pool, Flyway, JDBC/transaction infrastructure that simply did not exist in-process before D2) | **1.62 GB** (same order of magnitude as the pre-D2 1.70 GB peak, not higher - the heap cap and real Postgres traffic did not meaningfully change the dominant cost, which is still `--no-daemon` build process + forked JUnit worker + up to 2 concurrent Chromium instances) |
| `web` (Caddy) | ~15 MB | ~15 MB (unchanged) |
| `postgres` | ~78 MB (up from ~40 MB idle - the schema now actually exists, not just an empty cluster) | ~78 MB (essentially flat under real load - a handful of `runs`/`run_events`/`run_selected_tests`/`artifacts` row writes per test run is negligible next to the Chromium/Gradle footprint that dominates this measurement) |

**System-wide peak: ~1.71 GB** for the three containers - materially unchanged from the pre-D2 ~1.75
GB figure, confirming the concern the original measurement flagged ("zero margin for D2's Postgres
query/connection load") did not actually materialize: Postgres's own footprint under real per-run
read/write traffic is small enough to be noise next to the browser-automation cost that already
dominated. The **8 GB go/no-go recommendation below is confirmed, not just provisional** - re-run
against real, both caps and persistence active, not the earlier pre-caps/pre-persistence estimate.

**Disk**, also re-measured for real:

| Item | Size |
|---|---|
| `deploy-runner-service` image | 3.59 GB (unchanged - code changes are small next to the baked-in JDK/Chromium/repository weight) |
| `deploy-web` image | 89 MB (unchanged) |
| `postgres:17-alpine` image | 424 MB (unchanged) |
| `runner-data` volume, after 3 real runs (1 `SMOKE`, 1 `FIXTURE`, 1 `REGRESSION`) | ~3 MB (same order of magnitude as before) |
| `pgdata` volume, after those same 3 runs (new - D2 is the first time this volume ever held real schema/data rather than an idle empty cluster) | ~49 MB |

**~49 MB is the current footprint, not a measured growth rate** (review correction) - it is
overwhelmingly Postgres's own baseline cluster/WAL overhead that exists the moment the schema is
migrated, before a single run's own row is ever written, not the accumulated cost of those 3 runs'
`runs`/`run_events`/`artifacts` rows. Establishing the actual per-run growth rate needs comparing
`pgdata`'s size immediately after migration against its size after a much larger number of runs -
that comparison, and the retention policy it would motivate, is D4's own job, not this measurement's.
What this figure does confirm: even taken at face value as if it were all real per-run growth, ~49
MB is negligible next to the image sizes and the already-budgeted rebuild-and-redeploy headroom -
the **20 GB floor stated below still holds** regardless of which interpretation of this number turns
out to be closer to true per-run growth once D4 actually measures it.

## 6. Data retention (D4.1)

The unbounded-growth concern §5 flagged is now closed. `runs`/`run_events`/`run_selected_tests`/
`artifacts` rows and their on-disk artifact/log/raw-event files are pruned by a background
`RetentionScheduler` (`@Scheduled(fixedDelayString = "${runner.retention-cleanup-interval}",
initialDelay = 0)`, default hourly), with two independent, precedence-ordered protocols:

- **Full-run cleanup** - a terminal run is deleted once either bound fails first: `finished_at`
  older than `runHistoryMaxAge` (default 30 days), or it falls outside the newest
  `runHistoryMaxCount` (default 500) visible terminal runs, ranked `finished_at DESC,
  requested_at DESC, run_id DESC` for a deterministic tie-break. Takes precedence over artifact
  purge - the two protocols never claim the same run in one sweep.
- **Artifact-only purge** - a shorter, independent window (`artifactMaxAge`, default 14 days,
  validated to never exceed `runHistoryMaxAge`), removing just a run's artifact files/metadata
  while its row and event history live on.

Both are crash-safe: an atomic conditional `UPDATE ... WHERE <tombstone column> IS NULL` lets a
single sweep safely resume a run left tombstoned by an earlier, crashed process, and a tombstoned run
is excluded from every public read path (`findById`/`findAll`, and SSE replay through the same
lookup) the instant the tombstone commits - not only once its files finish deleting. A second,
in-process `ReentrantLock` (non-blocking `tryLock`) additionally guards a whole real sweep's own
duration, since the tombstone `UPDATE` alone can't tell "resuming a crashed sweep" apart from
"racing a sweep that is genuinely still running right now." A shared per-run `SELECT ... FOR UPDATE`
row lock coordinates artifact ingestion against a concurrent purge. Manual dry-run preview and
on-demand trigger are exposed as admin-only, `@Hidden` (kept out of the public OpenAPI doc),
rate-limited (`runner.retention-rate-limit`, 10/hour by default) endpoints:
`GET`/`POST /api/v1/retention/{preview,run}`.

This directly addresses §5's "no margin for D4's retention job running alongside a live suite run"
caveat and the `runner-data`/`pgdata` unbounded-growth concern - both now have a real, tested upper
bound rather than growing forever. See `docs/RELEASE_EVIDENCE.md`'s "Faza D4.1" section for the live
verification transcript.

## 7. Backup and restore (D4.5)

PostgreSQL is the only real source of truth this deployment needs to protect (run-lifecycle status/
history/events all live there - see §3; the `runner-data` volume holds only artifacts/logs/raw-event
files, already bounded by §6's retention and reconstructible by re-running tests, so it is
deliberately **not** part of this backup - a decision made explicitly, not by omission).

**Mechanism - a standalone image, not a JVM-embedded `@Scheduled` job.** `deploy/backup/` is a
separate `postgres:17-alpine`-based image (so `pg_dump`/`pg_restore` always match the exact server
major version §1's `postgres` service runs, never a second, independently-pinned client version that
could silently drift) carrying `age` and `rclone` (both real Alpine packages, no third-party binary
download). It is invoked on demand - `docker compose -f docker-compose.yml -f
docker-compose.backup.yml run --rm backup` - normally from a host-level **systemd timer** (preferred
over cron for this: `Persistent=true` catches a run missed while the host was down, a real timeout,
a clear unit exit status, and journald evidence for free - documented in the D4.6 operational
runbook, not here: GitHub Actions cannot reach a real production host any more than it could for the
D4.4 performance-baseline workflow - see `docs/RELEASE_EVIDENCE.md`'s D4.4.2b section for the
identical constraint already established there). Kept entirely separate from `runner-service`'s own
image and container lifecycle - a backup failure must never be coupled to the application's own
restart/health behavior, and `pg_dump`/`age`/`rclone` have no reason to live inside a JVM image at
all.

**Two separate Compose files, not one.** A `profiles: ["backup"]`-gated service in the base
`docker-compose.yml` isn't enough: Docker Compose interpolates every `${VAR:?message}` in a file's
service definitions while building its config model *regardless of which profiles are active*, so a
bare `docker compose -f docker-compose.yml up` (no `--profile backup`, no `BACKUP_*` vars set) would
fail outright the moment those required vars were interpolated. The whole `backup` service instead
lives in `deploy/docker-compose.backup.yml`, only ever combined in explicitly
(`-f docker-compose.yml -f docker-compose.backup.yml`) - the base file validates cleanly with zero
`BACKUP_*` vars set. `backup` is wired to a second, plain (non-internal) `backup-egress` network
alongside the `internal: true` `data` network - `data` alone would let it reach `postgres` but gives
it no route to the internet, so a real off-site upload could never succeed; `postgres`/
`runner-service`/`web` are untouched, still exactly as isolated as before.

**The pipeline (`backup.sh`)**: `pg_dump --format=custom` streams directly into `age --encrypt
--recipient <public-key>` - **no plaintext database dump is ever written to disk**, at any point, on
either side of the backup/restore round trip (`restore.sh` mirrors this: `age --decrypt` pipes
directly into `pg_restore`'s own stdin). The encrypted archive is written to a `*.partial` path first
and atomically renamed to its final `*.dump.age` name (now `runner-backup-<timestamp>-<random-hex>
.dump.age` - a random suffix added alongside the timestamp as defense in depth against a same-second
filename collision) only once the pipe completes successfully. It is then uploaded via `rclone
copyto` to an S3-compatible remote (Backblaze B2 is the reference target, but
`BACKUP_REMOTE`/`BACKUP_BUCKET`/`BACKUP_PREFIX` are plain rclone config, so this is not hard-wired to
one provider), the upload is independently re-verified with `rclone check` (a real checksum
comparison, not just trusting `copyto`'s own internal retry logic), and only *then* is the local copy
deleted. The whole cycle runs under a non-blocking `flock` held for the script's own duration, so two
concurrent invocations (an overlapping systemd timer run and a manual one, say) can't race the same
filename, an in-flight `*.partial`, or a double upload - a second, genuinely concurrent invocation
fails immediately and loudly instead.

**Retry-before-cleanup.** `backup.sh` attempts to upload-and-verify every existing local
`*.dump.age` file *first*, before even starting a new dump; a file only ever leaves local disk once
that has genuinely succeeded - it is never discarded on age alone without a confirmed upload. A
stale `*.partial` (only ever possible from a crashed prior run, since `flock` excludes a genuinely
concurrent one) is always removed unconditionally on every run.

**Disk-budget guard - sized to the actual dump, not a flat floor alone.** Required free space is
`BACKUP_MIN_FREE_BYTES` (general headroom) + the source database's own real, live-measured size
(`SELECT pg_database_size(current_database())` - a conservative estimate, since `pg_dump`'s own
compressed custom-format output is typically *smaller* than this raw figure) +
`BACKUP_DUMP_RESERVE_BYTES` (an additional flat safety margin, default 100 MiB). If that fails -
most plausibly meaning uploads have been failing repeatedly and unverified archives have piled up,
though it could just as well mean the database has genuinely outgrown its old defaults - the script
fails closed and refuses to start a new dump at all, rather than risking real disk exhaustion mid-dump
or silently discarding an unconfirmed backup just to make room.

**Retention**: primarily the bucket's own lifecycle policy (configured on the bucket itself, outside
this repo's control - not yet configured, since no real bucket exists before D5); `backup.sh` also
enforces an optional, independent second bound (`BACKUP_RETENTION_DAYS`, `rclone delete --min-age`)
as defense in depth, made deliberately non-fatal on its own failure - a transient cleanup failure
must never make an already-successful, already-verified backup report as failed.

**Key management - recipient/identity split, deliberately asymmetric.** `backup.sh` only ever holds
the **public** age recipient (`BACKUP_AGE_RECIPIENT`, set in `deploy/.env` - not a secret, safe to
have on the production host). The matching **private** identity key never resides permanently in the
standing deployment - it is generated once, offline, via `age-keygen`, and kept outside the
deployment entirely (a password manager, per the user's own explicit D4.5 decision), supplied only
at actual restore time as a mounted, read-only file - genuinely present on the host, temporarily, for
the duration of that one restore invocation, never part of the *standing* config.
`docker-compose.backup.yml` deliberately defines no standing `restore` service for exactly this
reason; a restore is always a manual, explicit `docker compose ... run --rm` invocation with that
mount added on top (see that file's own header comment for the full command).

**Restore safety.** `restore.sh` refuses to run without an explicit backup identifier as its first
argument (never guesses "latest"), and that argument is validated against a strict regex matching
`backup.sh`'s own naming convention before it is used to build any local or remote path. The restore
itself runs under `pg_restore --single-transaction` (which, per PostgreSQL's own `pg_restore` docs,
implies `--exit-on-error`) plus `--no-owner --no-privileges` - either the whole archive applies, or
none of it does. A required `RESTORE_EXPECTED_DATABASE` is cross-checked against what Postgres itself
reports via `SELECT current_database()` - never just a parsed connection-string value - and the
script refuses outright on any mismatch, before touching rclone/age/Postgres at all. The
destructive-restore acknowledgment, `RESTORE_CONFIRM_DESTRUCTIVE`, must equal exactly
`<real-database-name>:<backup-identifier>` (the two facts specific to *this* restore), never a
generic `yes` that could be silently reused across a completely different incident - the required
value is printed in the script's own refusal message.

**RPO/RTO**: not yet measured against a real production host or a real off-site round trip (no real
deployment exists before D5) - see the D5 acceptance list below for what closes this out for real.
Today's automated drill (below) proves the mechanism end to end but times only a local,
Docker-Desktop-speed round trip, not a representative RPO/RTO figure.

**Live-verified against a real system.** `BackupRestoreDrillTest`
(`runner-service/src/databaseIntegrationTest/`) builds the real `deploy/backup` image once per
class, then eight separate scenarios, all against real Testcontainers
`postgres:17-alpine` instances and the real `docker run --network container:<id>` mechanism (sharing
a Postgres container's own network namespace - the same way a production host reaches `postgres` by
Compose DNS, just addressed as `127.0.0.1:5432` instead), proving: the full happy-path round trip
(real Flyway migrations plus one real row in every table); an invalid backup identifier is refused
before `restore.sh` ever touches rclone/age/Postgres; a destructive restore into a target with an
existing, populated `runs` table is refused both with no acknowledgment and with a present-but-wrong
one; a wrong-but-plausible `RESTORE_EXPECTED_DATABASE` is refused before anything else runs;
decrypting with the wrong identity key fails loudly with the target receiving nothing; a genuinely
failed upload leaves the local encrypted archive on disk, unretried-but-intact, and a second
`backup.sh` run against that same survivor genuinely retries and uploads it; and a real concurrent
invocation against the same `flock` lock file fails fast rather than blocking, racing, or corrupting
anything. See `docs/RELEASE_EVIDENCE.md`'s D4.5 section for the full transcript.

**Required for the D5 acceptance pass (not done here - no real production host or bucket exists
yet)**: dump a real production Postgres; upload it off-site for real, against a real Backblaze B2
bucket; download it back into a fresh, isolated Postgres; verify the real Flyway schema history, real
run/event counts, and one specific known run; confirm a missing/purged artifact file's own graceful
behavior is unaffected by a database-only restore (artifacts are deliberately not backed up, per this
section's own opening decision); document the actually-measured RPO/RTO from that real run - a
materially stronger portfolio signal than a local backup file sitting on the same VPS it is supposed
to survive the loss of.

## Verified

Every claim above was checked against a real, running Docker Compose stack, not reasoned about from
the Compose/Dockerfile/Caddyfile source alone. Confirmed live:

- Both images build; the whole Caddy site block is wrapped in one `route { }` block so `/api/*` and
  `/actuator/health` are proxied rather than silently caught by the SPA's `try_files`/`file_server`
  fallback (Caddy evaluates directives in its own fixed internal order, not file order).
- SSE streams through Caddy in real time, not buffered/delayed.
- Artifact/log files land on, and survive a container restart via, the named `runner-data` volume.
- A root `.dockerignore` keeps the host's own `node_modules` and other dev-only content out of the
  build context (215 MB down to under 1 MB; neither image contains any node tooling).
- No host port is published in the base `docker-compose.yml`; `deploy/docker-compose.debug.yml` is a
  separate, clearly-labeled override for local troubleshooting only.
- The `edge`/`data` network split is real, not just declared: `web` cannot reach `postgres:5432`,
  `runner-service` can.
- `runner-service`'s Dockerfile uses exec-form `CMD ["java", "-jar", ...]` against a fixed jar path,
  so `docker stop`/`restart` reaches the JVM directly (`java` as PID 1, graceful shutdown completes
  in ~1 second) rather than being swallowed by a shell-form `CMD`.
- `RunAvailabilityPolicy` genuinely hides `LOCAL` from `/api/v1/capabilities` and rejects it at
  `POST /api/v1/runs` with `400` under `RUNNER_DEPLOYMENTPROFILE=PORTFOLIO`, through the full
  Caddy-fronted stack, without regressing the network isolation above.
- `SITE_ADDRESS` is passed to `web` as a bare variable reference (`environment: [SITE_ADDRESS]`, no
  `:-` default) - Compose's `${VAR:-}` form always sets an empty string when unset rather than
  omitting the variable, and Caddy's own placeholder default only applies when it's genuinely
  undefined; an empty value instead parses as a bare global-options block and fails Caddy's startup
  entirely.
- The named `caddy-data`/`caddy-config` volumes are real and populated, avoiding a re-issued
  certificate (and Let's Encrypt rate limits) on every container recreate.
- `runner-service`'s Dockerfile sets `RUNNER_DEPLOYMENTPROFILE=PORTFOLIO` itself (fail-closed by
  default, with `docker-compose.yml` repeating the same value as belt-and-braces documentation, not
  the only thing enforcing it) - a bare `docker run` with no Compose override still returns `PUBLIC`
  only from `/api/v1/capabilities`.
- `runner-service` sets `init: true` (tini as PID 1) so a real run's process tree - the Gradle
  wrapper, its daemon, and any spawned Chromium instances - is fully reaped on both normal
  completion and mid-flight cancellation, with no zombies or orphans left behind.

**Still open**: a real domain to actually exercise TLS issuance end to end (`SITE_ADDRESS` has only
been verified unset, i.e. plain `:80` - D5 buys the domain); re-running the RAM/disk measurement in
§5 before the D5 purchase; every base image (`eclipse-temurin:21-jdk-jammy`, `node:24-slim`,
`caddy:2-alpine`, `postgres:17-alpine`) is pinned to a moving tag, not an exact version+digest, so two
builds of the identical commit aren't guaranteed to produce an identical deployment today - pin each
to `image:x.y.z@sha256:...` before D5, ideally with Renovate/Dependabot opened against the digest.
