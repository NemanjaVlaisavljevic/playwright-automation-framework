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

**Verified live, both ends of the boundary, not just unit-tested**: started a real `runner-service`
with `RUNNER_DEPLOYMENTPROFILE=PORTFOLIO` - `GET /api/v1/capabilities` returned `PUBLIC` only (no
`LOCAL` entry at all, not an empty one), `POST /api/v1/runs` with `{"environment":"LOCAL",...}`
correctly `400`s (`Unsupported environment/suite combination: LOCAL/JOURNEY`), and a `PUBLIC`/
`SMOKE` submission still queued and ran normally (cancelled once queued, to avoid leaving a process
behind - confirmed no orphaned `java`/Gradle-daemon-launched process survived afterward). Also
reconfirmed the unrestricted default (no env var set) still advertises both `PUBLIC` and `LOCAL`
exactly as before, so existing local-development/CI behavior is unchanged.

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

**A real regression this design would otherwise have shipped, caught in review**: the dashboard
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

**A real design risk caught in review before any code existed**: splitting the migration into "move
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

**D2.1 - DONE 2026-09-06.** Reviewed once (four gaps folded directly into `V1` before it shipped
anywhere - the fourth table, the artifact NOT-NULL/schema_version/created_at columns, a full
lifecycle-mirroring CHECK-constraint set, and a full one-case-per-constraint test matrix, all
described inline in the migration/schema sections above) and once more after that (container-count/
`@Testcontainers` cleanup, a `chk_runs_status` test that could legitimately fail on constraint-
evaluation-order grounds - PostgreSQL does not guarantee which of several simultaneously-violated
CHECK constraints it reports first, fixed by asserting the generic `chk_runs_` prefix instead of one
specific name, plus splitting `databaseIntegrationTest` into its own parallel CI job). 26/26 green
against a real Testcontainers Postgres, reconfirmed stable across repeated runs.

**D2.2 - first pass DONE 2026-09-06.** `JdbcRunStore` (`repository/jdbc` package) - the single
component the review demanded, not a mechanical two-collaborator port of today's `RunRepository`/
`RunEventAppender` split: one method, one transaction, `SELECT ... FOR UPDATE` on the `runs` row,
sequence allocated from that same locked row, the event inserted, the run row updated, then
committed - reusing `Run.transitionTo`/`RunStateMachine` unchanged for validation, so this component
adds no parallel business-rule copy of its own. Deliberately still not a Spring bean (see its own
Javadoc) and not yet wired to any live subscriber hub - both are explicitly D2.3's job.

Verified directly against a real Testcontainers Postgres, not reasoned about: a `queue()` +
`findById()` round trip (including a `CUSTOM` run's selected-test snapshot), the inserted
`RUN_QUEUED` event's JSON payload round-tripping byte-for-byte back through `ObjectMapper` into an
equal `RunnerEvent`, a no-event transition allocating no new sequence, an event-bearing transition
allocating exactly the next gapless sequence, a genuine two-thread race (two real connections both
attempt to finalize the same `RUNNING` run to a different terminal status at the same instant) where
exactly one of the two attempts ever applies and exactly one `RUN_FINISHED` event is ever inserted -
never both, never neither - and a forced event-insert conflict (a pre-existing colliding sequence)
proving the *entire* transaction rolls back, leaving the run's status completely untouched rather
than partially transitioned. All green on three separate runs (checking specifically for concurrency
flakiness), and the full existing Java gate (root + all three `runner-*` modules, unchanged) stayed
green throughout - this phase touched no file the live application actually loads yet.

**D2.2 review round (2026-09-06, same day) - 3 P1s, all fixed, D2.2 now formally closed:**

1. **[P1] The class's own Javadoc wrongly called the DB row lock "the per-run lock the protocol
   needs"** - `SELECT ... FOR UPDATE` is released at `COMMIT`, so it serializes concurrent database
   *writers* on the same `runId` (everything this class's own concurrency test actually proves) but
   cannot by itself close the "publish after commit, before unlock" window the protocol's live-SSE
   half needs - that's about coordinating this store's commit with the in-process `RunEventHub`
   subscribe path, which a DB lock can't reach. Corrected the Javadoc, and - so D2.3's future
   external per-run lock has everything it needs without re-deriving or re-reading anything - every
   write method now returns a new `CommittedRunChange(Run run, RunnerEvent event)` (the event
   nullable for a no-event transition) instead of a bare `Run`/`Optional<Run>`.
2. **[P1] No append-only path for `TEST_*`/`STEP_*` events** - `transitionIfNonTerminal` couples
   every event to a lifecycle transition, so recording a test/step event through it would have
   forced a real `UPDATE runs` (bumping `version`, rewriting every lifecycle column) for every
   single test in a run, for no reason. Added `appendEventIfNonTerminal(runId, eventFactory)`:
   allocates the next sequence under the same row lock, inserts the event, advances only
   `next_event_sequence` - status/timestamps/`version` untouched - and rejects any `RUN_*` event
   type outright (that path is `queue()`/`transitionIfNonTerminal`'s job, not this one's). Returns
   empty once the run is already terminal, mirroring `RunEventAppender`'s own closed-journal
   contract.
3. **[P1] An event factory could silently corrupt the row/event correlation** - nothing previously
   checked that the `RunnerEvent` a caller-supplied factory returned actually matched the `runId`/
   `sequence` it was allocated for, or that a lifecycle transition's event carried the right type/
   outcome (a `RUNNING` transition handed a `RUN_FINISHED` event, or a `SUCCEEDED` transition handed
   a `RUN_FINISHED` whose own `runOutcome` said `FAILED`, would previously have been written as-is).
   Added `requireMatchingEvent` (runId/sequence, used by all three write methods) and
   `requireLifecycleEventMatches` (RUNNING &rarr; `RUN_STARTED`; any terminal status &rarr;
   `RUN_FINISHED` with the matching `runOutcome`) - a violation throws before any SQL runs, and (via
   the same transaction) rolls back anything already written in that call. Six new tests cover wrong
   `runId`, wrong `sequence`, and wrong type/outcome, each asserting both the exception and that the
   run's own status/event count survived untouched.

Also fixed, both flagged as worth doing before the cutover rather than after: `findAll()` batch-loads
every run's `run_selected_tests` in one query grouped by `run_id` (was one query per run - an N+1
that would only get more expensive as history grows); every timestamp is now truncated to
microseconds (`Instant.truncatedTo(ChronoUnit.MICROS)`) at the point it first enters this class -
`TIMESTAMPTZ` only stores microsecond precision, so an untruncated nanosecond-precision `Instant`
would otherwise have made `queue()`'s own in-memory return value silently disagree with what a later
`findById()` re-read produces. A new test using a literal nanosecond-precision `Instant` asserts both
sides agree on the truncated value.

Verified after all fixes: the same three-Testcontainers-run stability check (now 16 `JdbcRunStoreTest`
cases, up from 6), full Java gate green throughout.

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

Verified: the full existing `RunEventBrokerTest` suite (concurrent-append stress test, the
deterministic blocking-store race test, slow-consumer disconnect, resume-sequence validation,
terminal-vs-non-terminal resume behavior, shutdown) all still pass rewritten against a
`FakeRunLifecycleStore` (a new, behaviorally-faithful in-memory double sharing the exact same
`RunEventValidation` calls `JdbcRunStore` itself uses, so the fake can never silently accept or
reject something the real store wouldn't) instead of a real file journal - every test needed
adapting to call `queue()` first (a real `run_events` row now requires its `runs` parent to exist
first, thanks to the schema's own foreign key, which the old file journal never enforced), and the
300/260-event stress tests moved from repeatedly "re-queuing" one run (a real run can only be queued
once) to the append-only `TEST_STARTED` path instead. `RunServiceTest`/`RunLifecycleCoordinatorTest`
rewritten the same way; two of `RunServiceTest`'s pre-cutover "emergency ERROR" tests were redesigned
around the new, intentionally different failure mode (a permanently-failing `RUN_FINISHED` write now
leaves a run stuck at its last known-good status rather than falsely `SUCCEEDED`/showing a
fabricated `ERROR` - see the reasoning in `RunLifecycleCoordinator`'s own Javadoc). Full gate green
throughout: root `test` (45), all three `runner-*` module suites (`runner-contract` 50, `runner-
listener` 18, `runner-service` 274), and `databaseIntegrationTest` (42, including two new
`JdbcRunStore` read-method cases) - zero failures across all of them, `git diff --check` clean.

**Explicitly not done yet, a real remaining gap before D2.3 can be called fully closed**: none of
this is wired into the *live* application yet. `JdbcRunStore` is still not a Spring `@Component` (no
real `DataSource`/`TransactionTemplate` bean exists for it to be constructed from), and
`RunnerServiceApplication` still excludes `DataSourceAutoConfiguration`/`FlywayAutoConfiguration` -
exactly as D2.1/D2.2 left it. `OpenApiContractTest`/`ServerBindingTest` (the two existing full-
`@SpringBootTest`-context tests) now carry a `@MockitoBean RunLifecycleStore` specifically so this
Docker-free, real-Postgres-free ordinary `test` task keeps working without a real store bean ever
needing to be constructed - a legitimate, targeted fix for tests that are about the OpenAPI document
and server binding, not the store. The **actual production/local wiring** - re-enabling those two
autoconfigurations, giving `JdbcRunStore` a real `@Component` constructor, adding
`spring.datasource.*` properties, and updating `docker-compose.yml` so `runner-service` actually
points at its sibling `postgres` service - has not been done. Once it is, every local `bootRun` and
`dashboardE2eTest` (which boots a real `runner-service` via `bootJar`/`java -jar`, bypassing Compose
entirely) will need a real, reachable Postgres too - `dashboardE2eTest`'s own environment will need a
Testcontainers-managed Postgres added to it, which has not been done either. Neither gap affects the
PR-blocking `quality-gate.yml` gate today (`dashboardE2eTest` only runs from the separate, non-
blocking `dashboard-e2e.yml` workflow), but both are real, tracked follow-ups before D2.3 is fully
closed - not silently deferred. Also still outstanding, per the design section above: the full
concurrent-appends-under-real-subscriber-traffic/crash-recovery-replay acceptance list, which needs
the live wiring to exist first before it can be exercised for real (as opposed to `FakeRunLifecycleStore`).

**D2.3 review round (2026-09-06) - all findings fixed before live wiring.** A second review of the
business-logic reconciliation above confirmed the cutover architecture itself is sound, but found one
real correctness gap and five hardening gaps that had to close before live wiring could be safe to
build on top of:

- **[P1] Lifecycle event validation was conditional, not unconditional.** `requireLifecycleEventMatches`
  used to run only inside `if (eventFactory != null)`, so a caller could commit `RUNNING`/a terminal
  status with a `null` factory (silently losing the event the replay protocol requires) or commit
  `STARTING` with an arbitrary attached event (nothing downstream expects one). Fixed by making
  `RunEventValidation.requireLifecycleEventMatches` itself unconditional over every `RunStatus`: `STARTING`
  now requires `event == null`; `RUNNING` requires a non-null `RUN_STARTED`; every terminal status
  requires a non-null `RUN_FINISHED` with a matching outcome; `QUEUED` is rejected outright (it has its
  own dedicated `requireQueuedEvent` via `queue()`). Both `JdbcRunStore` and `FakeRunLifecycleStore` call
  it the same unconditional way. New coverage: a fast, DB-free `RunEventValidationTest` (9 cases) plus
  three new `JdbcRunStoreTest` rollback cases against a real Postgres (`transitionToRunningWithoutAnEventFactoryIsRejected`,
  `transitionToATerminalStatusWithoutAnEventFactoryIsRejected`, `transitionToStartingWithAnEventFactoryIsRejected`).
- **[P2] A transition function could return a run with a different identity.** Nothing stopped a
  hand-rolled `UnaryOperator<Run>` from constructing an arbitrary `Run` instead of only changing
  status/timing/result. Fixed with a new `RunEventValidation.requireSameIdentity(before, after)`,
  called by both `JdbcRunStore.transitionIfNonTerminal` and `FakeRunLifecycleStore.transitionIfNonTerminal`
  right after the transition runs, rejecting any change to `runId`/`environment`/`suite`/`requestedAt`/
  `selectedTests`. Covered by both `RunEventValidationTest` and a dedicated `JdbcRunStoreTest` case.
- **[P2] `FakeRunLifecycleStore`'s `Run` snapshot had no JMM visibility guarantee.** `findById`/`findAll`
  read `RunRecord.run` without the per-run lock (by design, so reads never serialize on a writer), but
  the field was a plain, non-`volatile` reference - fixed by marking it `volatile`.
- **[P2] The per-run lock map grew forever.** Both `RunEventBroker` and `FakeRunLifecycleStore` kept a
  `ConcurrentHashMap<String, Object>` with one entry per `runId` ever seen, never removed (removal is
  itself unsafe: a lock object must never be replaced/removed while another thread might still be
  synchronized on it). Replaced with a new shared `RunLockStripes` - a fixed 256-entry array of monitor
  objects, indexed by `runId.hashCode()` - bounding memory at the cost of occasional, harmless
  false-positive serialization between unrelated runs that happen to hash to the same stripe.
- **[P2] `JdbcRunStore.queue()`'s SQL insert loop iterated the caller's raw parameter, not the
  already-validated copy.** Fixed to iterate `run.selectedTests()` (the `List.copyOf`'d, validated value
  from the constructed `Run`) instead of the `selectedTests` method parameter directly.
- **[P2] The two-connection concurrency test wasn't a deterministic proof of the row lock.** The
  existing latch-based `exactlyOneOfTwoConcurrentTerminalAttemptsOnTheSameRunWins` only proved the two
  attempts didn't corrupt each other - a scheduler could in principle serialize them without either ever
  blocking on the lock, and the test would still pass. Added a new
  `aSecondTransactionBlocksUntilTheFirstsRowLockIsReleased`: it opens a second raw JDBC connection, takes
  `SELECT ... FOR UPDATE` on the row itself and holds it open, proves a concurrent
  `transitionIfNonTerminal` call cannot complete within a short timeout while that lock is held, then
  commits the locking connection and proves the pending call then completes. The original latch-based
  test is kept as a stress companion, not replaced.
- **Minor cleanup**: `RunEventHub`'s Javadoc no longer references the deleted `FileBackedRunEventJournal`;
  the now-fully-unused `RunEventReader` interface is deleted; `JdbcRunStore`'s class Javadoc no longer
  claims "every timestamp is truncated to microseconds" - it now spells out that only `Run`'s own
  `requestedAt`/`startedAt`/`finishedAt` columns are truncated, while `RunnerEvent.timestamp` inside the
  `jsonb` payload stays nanosecond-precision (the authoritative copy) and `run_events.occurred_at` is a
  separate, microsecond-precision secondary index column, never re-parsed back into a `RunnerEvent`.

Verified after all fixes: `JdbcRunStoreTest` now 21 cases (up from 16), `RunEventValidationTest` new (9
cases), full gate green throughout (root `test` 45, `runner-contract` 50, `runner-listener` 18,
`runner-service` `test` 274 + new `RunEventValidationTest` 9, `databaseIntegrationTest` 21 + 26 = 47),
`git diff --check` clean. The live-wiring gap in the section above is unaffected by this round - still
the explicit next step.

**D2.3 - final part: real Spring wiring, Compose, dashboardE2eTest Postgres, acceptance matrix - DONE
2026-09-06.** Everything the review round above gated is now closed:

- **Real Spring wiring.** `RunnerServiceApplication` no longer excludes `DataSourceAutoConfiguration`/
  `FlywayAutoConfiguration`. `JdbcRunStore` is now a real `@Component` - `JdbcTemplate` comes from
  Spring Boot's own `JdbcTemplateAutoConfiguration`, `TransactionTemplate` from
  `TransactionAutoConfiguration$TransactionTemplateConfiguration` (given the single
  `PlatformTransactionManager` `DataSourceTransactionManagerAutoConfiguration` creates for the
  auto-configured `DataSource`), `ObjectMapper` from the existing `spring-boot-starter-json`
  autoconfiguration - no manual `@Bean` wiring needed for any of the three.
- **`application.yml`** carries local-`bootRun` `spring.datasource.*` defaults
  (`jdbc:postgresql://localhost:5433/runner`, `runner`/`runner`) - port 5433, not Postgres's usual
  5432, because this exact machine already runs its own unrelated Postgres Windows service on 5432
  (confirmed live: `docker run -p 127.0.0.1:5432:5432 postgres` failed outright with a port-in-use
  error). New `runner-service/build.gradle` tasks `localPostgresUp`/`localPostgresDown` start/stop a
  matching throwaway `postgres:17-alpine` container for local development - a plain `docker run`/
  `docker rm -f` pair (not docker compose; a single container needs none of its orchestration), polling
  `pg_isready` with no fixed blind delay, the same convention `localSutHealth` already uses for the RBP
  stack.
- **`deploy/docker-compose.yml`** - `runner-service` now depends on `postgres` with
  `condition: service_healthy` (not just container-started; a fresh `docker compose up` would otherwise
  race the JVM's very first connection attempt against a Postgres that hasn't finished initializing),
  and carries `SPRING_DATASOURCE_URL`/`_USERNAME`/`_PASSWORD` env vars reusing the exact same
  `POSTGRES_DB`/`POSTGRES_USER`/`POSTGRES_PASSWORD` values the `postgres` service itself is configured
  from (never repeated as separate literals that could drift apart).
- **`dashboardE2eTest`** - new `DashboardE2eDatabase` owns one Testcontainers `postgres:17-alpine`
  container, lazily started and shared for the whole suite's JVM (both `DashboardE2eEnvironment`'s
  shared backend and `BackendUnavailableE2eTest`'s own isolated one point at the same container - a
  run's history surviving `BackendUnavailableE2eTest`'s own repeated stop/restart on purpose, not a
  leak between test classes); Flyway migrates on the launched jar's own startup, nothing runs a
  migration directly. Stopped from `DashboardE2eEnvironment`'s own root-context close, the one point
  guaranteed to run after every test class finishes; if a filtered run only ever executes
  `BackendUnavailableE2eTest`, Testcontainers' own Ryuk reaper is the fallback, same as everywhere else
  in this codebase an unmanaged container is used.
- **`OpenApiContractTest`/`ServerBindingTest`** (the two Docker-free, full-`@SpringBootTest`-context
  tests) now re-exclude `DataSourceAutoConfiguration`/`FlywayAutoConfiguration` at the test level (via
  `spring.autoconfigure.exclude`), alongside the existing `@MockitoBean RunLifecycleStore` - together,
  the ordinary `test` task still never needs a real Postgres.
- **Dead config removed.** `RunnerProperties.journalDir`/`runner.journal-dir` (unused since
  `FileBackedRunEventJournal` was deleted in the earlier cutover) and the Dockerfile's matching
  `RUNNER_JOURNALDIR` env var are gone; every `RunnerProperties`/`new RunnerProperties(...)` test call
  site updated to match.
- **Acceptance matrix** - a new `RunEventBrokerJdbcAcceptanceTest` (`databaseIntegrationTest`) proves,
  against a real `RunEventBroker` wrapping the real `JdbcRunStore` and a real Testcontainers Postgres
  (not `FakeRunLifecycleStore`): concurrent appends under real subscriber traffic deliver every event
  exactly once, in order, matching what Postgres itself persisted
  (`concurrentAppendsWithALiveSubscriberDeliverEveryEventExactlyOnceInOrder`); a replay/subscribe race
  against a concurrent append serializes deterministically, not just usually
  (`replayAndSubscribeBlocksAConcurrentAppendUntilTheSubscriberIsRegistered`); and a run's complete
  history, committed with no subscriber ever attached (simulating a crashed/never-connected subscriber
  process), is fully recovered by a brand-new `RunEventBroker` instance (fresh in-memory `RunEventHub`
  state, same underlying store - modeling a real process restart) via `replayAndSubscribe` alone, purely
  from what Postgres persisted (`aRunsCompleteHistorySurvivesWithNoLiveSubscriberAndIsFullyRecoveredViaReplay`).

Verified live, beyond the automated suites:
- **A real `bootRun`** against `localPostgresUp`'s container: Flyway migrated on startup, Hikari
  connected, a real `SMOKE` run was queued/started/cancelled through the actual REST API, and its full
  event timeline (`RUN_QUEUED` through `TEST_*`/`STEP_*` through `RUN_FINISHED`, 19 gapless sequences)
  was confirmed by querying Postgres directly (`psql`) - not just trusting the API response.
- **The real `deploy/docker-compose.yml` stack** (`docker compose up --build`, with a local-only,
  gitignored `.env`): `postgres` reported healthy, `runner-service` then started, connected, migrated,
  and served `/api/v1/capabilities` correctly scoped to the `PORTFOLIO` profile (no `LOCAL`) - proving
  `depends_on: condition: service_healthy` and the `SPRING_DATASOURCE_*` wiring both work end to end.
  (The `web`/Caddy container failed to bind port 80 on this specific machine - an unrelated, pre-existing
  local port conflict with another service already listening there, not a regression in this change.)
- **The full `dashboardE2eTest` suite** (18 test classes, real Chromium, real backend jar, real
  Testcontainers Postgres) - passed only after one real, non-obvious fix: this root project has no
  Spring Boot BOM of its own, so its `testcontainers-bom:1.20.4` import resolved the *actual*
  `org.testcontainers:testcontainers` artifact at its own declared 1.20.4 rather than the 2.0.5 Spring
  Boot 4.1.1's BOM transitively forces for `runner-service`'s `databaseIntegrationTest` (only that one
  artifact moves - `postgresql`/`jdbc`/`database-commons` stay on 1.20.4 either way). 1.20.4's own
  older `docker-java` client sends a hardcoded `GET /v1.32/info` Docker API probe that this specific
  Docker Desktop build's npipe compatibility proxy answers with HTTP 400, failing every
  `DockerClientProviderStrategy` with "Could not find a valid Docker environment" - confirmed live by
  temporarily enabling `showStandardStreams` to see Testcontainers' own DEBUG log, since the default
  `false` (deliberately quiet for real dashboard-process output) otherwise swallows it entirely. Fixed
  with a single dependency `constraint` forcing `org.testcontainers:testcontainers:2.0.5` for this
  project only, mirroring Spring Boot's own override rather than bumping `testcontainersVersion` itself
  (no `testcontainers-bom:2.0.5` line exists to pin to - only the one core artifact moved). Not a defect
  in this cutover's own code at all - a pre-existing latent version mismatch between two Gradle projects
  that nothing had ever actually exercised Testcontainers from the root project to surface before.

**D2.3 review round 2 (2026-09-06) - three P1s and one P2, all fixed.** A third review confirmed the
architecture is sound (Postgres the single source of truth, status/event sharing one transaction, the
broker's per-run lock correctly spanning both commit-then-publish and replay-then-subscribe) but found
gaps the first review round's fixes did not close:

- **[P1] The store could still bypass `RunStateMachine`.** `transitionIfNonTerminal`'s caller-supplied
  `UnaryOperator<Run>` is trusted to call `Run.transitionTo` (which itself calls `RunStateMachine
  .requireTransition`), but nothing stopped a hand-rolled operator from constructing a `new Run(...)`
  directly with an arbitrary status instead - e.g. jumping straight from `QUEUED` to `SUCCEEDED`,
  skipping `STARTING`/`RUNNING` entirely. Neither `requireSameIdentity` (identity fields only) nor
  `requireLifecycleEventMatches` (event/status pairing only) would have caught it. Fixed with a new
  `RunEventValidation.requireReachableTransition(before, after)`, called right after `transition.apply`
  in both `JdbcRunStore` and `FakeRunLifecycleStore`: it independently calls `RunStateMachine
  .requireTransition(before.status(), after.status())`, and additionally requires an already-set
  `startedAt` to stay exactly as it was (nothing later, including a run's own terminal transition, may
  change it) - a corruption `Run`'s own compact constructor has no way to catch on its own, since it only
  ever validates one snapshot in isolation, never against what a specific transition started from.
- **[P1] A rollback test had gone falsely green.** `aConflictingEventInsertRollsBackTheStatusChangeToo`
  attempted a `STARTING` transition with a `RUN_STARTED` event attached - a combination round 1's own
  `STARTING` fix now rejects at the Java level, before any SQL runs at all, so the primary-key conflict
  this test exists to force was never actually reached; a broad `.isInstanceOf(RuntimeException.class)`
  assertion silently accepted the wrong exception (`IllegalArgumentException` is-a `RuntimeException`)
  as if it proved the intended thing. Fixed by first legitimately reaching `STARTING` with no event,
  then forcing the sequence conflict on the *next* transition (`STARTING -> RUNNING` with `RUN_STARTED`
  - the combination that actually requires one), and asserting the concrete `DuplicateKeyException`
  Spring's own exception translation produces, not just "some `RuntimeException`". A new
  `aFailingRunsRowUpdateRollsBackTheAlreadyInsertedEventToo` test proves the acceptance matrix's other
  direction too: a temporary Postgres trigger that unconditionally rejects `UPDATE runs` (installed and
  dropped within the one test method, never leaking into any other test sharing the static container)
  proves an already-succeeded event insert rolls back completely when the subsequent status update
  fails - not left durably committed on its own.
- **[P1] The crash-window acceptance scenario didn't simulate the actual crash window.** The existing
  `aRunsCompleteHistorySurvivesWithNoLiveSubscriberAndIsFullyRecoveredViaReplay` only proved a *cold*
  recovery (no subscriber ever attached, replay from sequence 0) - not the D2 design's actual scenario:
  a subscriber already saw events `1..N`, `N+1` commits, and the process dies *between that commit and
  `hub.publish`*. A new `aCrashBetweenCommitAndPublishIsFullyRecoveredViaReconnectReplay` test models
  this precisely: it commits the run's final event directly through the real `JdbcRunStore`, bypassing
  `RunEventBroker` entirely (skipping the broker's own `hub.publish` call is exactly what "the process
  died right there" means), confirms the original live subscriber genuinely never received it, then has
  a brand-new `RunEventBroker` instance (a fresh in-memory `RunEventHub` - modeling the actual process
  restart) reconnect with `afterSequence = N` and recover *exactly* the one missed event - no duplicate,
  no gap - closing immediately since it was the run's own `RUN_FINISHED`.
- **[P2] The concurrent-subscriber stress test didn't guarantee its own live-delivery half.** A plain
  `Thread.sleep(10)` before subscribing does not guarantee the publisher is still mid-flight when the
  subscription registers - on a slow/loaded runner all 200 appends could already be done, and the test
  would then pass purely through the replay path despite its name claiming to exercise live traffic.
  Fixed with two latches: the publisher writes a fixed 20-event prefix, signals it has, then blocks
  until released; the test only subscribes (and only then releases the rest) once that prefix is
  provably already durably written, guaranteeing the remaining 180 writes are genuinely concurrent with
  the subscription, not merely coincidentally overlapping it.
- **Minor cleanup**: `RunnerServiceApplication`'s Javadoc corrected from `localhost:5432` to the actual
  `localhost:5433`; `JacksonConfig` and a `runner-service/build.gradle` comment no longer reference the
  deleted `FileBackedRunEventJournal` (now `JdbcRunStore`).

Verified after all fixes: full gate green throughout (root `test`, `runner-contract`, `runner-listener`,
`runner-service` `test`, `databaseIntegrationTest` - `JdbcRunStoreTest` now 22 cases,
`RunEventBrokerJdbcAcceptanceTest` now 4), `spotlessCheck` and `git diff --check` clean.

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
- Still open: re-run the RAM/disk measurement in §5 with all of the above in place (heap caps did
  not exist when §5 was last measured), and once D2's persistence path is actually active - before
  the D5 purchase. Also still open: a real domain to actually prove TLS issuance end to end (no
  domain purchased yet - D5's job); everything above has only been verified with `SITE_ADDRESS`
  unset (plain `:80`), not against a real ACME challenge.

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

**Round 3 (D1 implementation, 2026-09-06)** - `RunAvailabilityPolicy` wired end to end, plus the
TLS/heap-cap task list from §5, all built and proven against a real rebuilt-and-run stack, not just
designed:

1. **`RunAvailabilityPolicy`** implemented as designed above and verified live (see §2's own
   "Verified live" note) - `RUNNER_DEPLOYMENTPROFILE=PORTFOLIO` genuinely hides `LOCAL` from
   `/api/v1/capabilities` and rejects it at `POST /api/v1/runs` with `400`, both through a real
   deployed `runner-service` and (below) through the full Caddy-fronted stack.
2. **A real bug found and fixed via this round's own Docker build**: `docker-compose.yml` originally
   wrote `environment: [SITE_ADDRESS=${SITE_ADDRESS:-}]` for `web` - Compose then always sets the
   container's `SITE_ADDRESS` to an empty string when `deploy/.env` doesn't define it, rather than
   omitting the variable entirely. Caddy's own `{$SITE_ADDRESS::80}` placeholder default only
   applies when the variable is genuinely *undefined*, not merely empty - an empty site address
   instead parses as a bare global-options block, and Caddy failed to start at all:
   `Error: adapting config using caddyfile: /etc/caddy/Caddyfile:10: unrecognized global option:
   encode`. Fixed by making it a bare variable reference (`environment: [SITE_ADDRESS]`, no `:-`
   default) and commenting out `SITE_ADDRESS=` in `.env.example` (a real value uncommented there,
   not merely present-and-empty, is what a future real domain requires) - reverified live: `web`
   started cleanly, logged `"server running"` on `:80`, no automatic-HTTPS warning beyond the
   expected "no domain configured" one.
3. Rebuilt both images (`docker compose build`, ~2 minutes, including a full `playwrightInstall
   --with-deps` + `:runner-service:bootJar` inside the `runner-service` image) and brought the full
   three-container stack up (host ports temporarily remapped to `8087`/`8443` for this verification
   only, since the host's port `80` was already legitimately held by this same machine's own
   long-running local RBP Docker stack, confirmed via `docker ps` before assuming a real conflict -
   never touched that unrelated container). Reverified through the real Caddy-fronted stack, not
   just against `runner-service` directly: `GET /api/v1/capabilities` through Caddy returned
   `PUBLIC` only (the `PORTFOLIO` profile propagating all the way through), `/actuator/health`
   returned `UP`, a client-side route (`/runs`) still resolved to the SPA shell, and the network
   split from Round 2 still holds (`web` timed out reaching `postgres:5432`; `runner-service`
   connected to it immediately) - the new `RUNNER_DEPLOYMENTPROFILE`/`SITE_ADDRESS` wiring didn't
   regress anything Round 2 already proved.
4. Confirmed the named `caddy-data`/`caddy-config` volumes are real and populated (Caddy's own log:
   `"autosaved config (load with --resume flag)"` against `/config/caddy/autosave.json`, the
   `/config` named volume, not container-local storage) - the persistence D1's task list called for
   to avoid re-requesting a certificate (and hitting Let's Encrypt's rate limits) on every container
   recreate.
5. Root `build.gradle`'s new `tasks.withType(Test).configureEach { maxHeapSize = '512m' }` and the
   backend Java changes were verified the ordinary way first (`./gradlew.bat spotlessApply test
   :runner-contract:test :runner-listener:test :runner-service:test`, all green, including new
   `RunAvailabilityPolicyTest`/expanded `RunRequestValidatorTest`/`CapabilitiesResponseTest`/a new
   `CapabilitiesControllerPortfolioProfileTest`) before the Docker round above - this Docker round
   is what additionally proves the *deployment wiring* (env var plumbing, Caddy routing under the
   new profile), not the policy logic itself, which the JVM-level tests already cover.

Cleaned up fully afterward: `docker compose down -v` (containers + all four named volumes removed),
both rebuilt images removed, the local `.env` (this round's own throwaway password) deleted,
`docker ps -a`/`docker images`/`docker volume ls` confirmed nothing from this round survives -
only the pre-existing, unrelated local RBP stack (never touched) remained. The host-port remap used
for verification was reverted in `docker-compose.yml` back to the real `80`/`443` afterward - it was
never the committed state, only a same-machine workaround for this round's own verification.

**Round 4 (review-round fixes, 2026-09-06, same day)** - a review of Round 3 found one P1 and
several P2/P3 gaps, all fixed and reverified against a rebuilt image/stack, not just reasoned about:

1. **[P1] The portfolio submission boundary had no permanent regression test** - every
   `RunServiceTest` case constructed `RunService` with `RunAvailabilityPolicy.localDev()`, so
   `RunAvailabilityPolicyTest`/`RunRequestValidatorTest`/`CapabilitiesResponseTest` alone would stay
   green even if a future change accidentally dropped the validator call from `RunService.submit`
   itself. Added `rejectsALocalSubmissionUnderThePortfolioProfileWithNoSideEffects` (asserts
   `UnsupportedRunCombinationException`, an empty repository, zero emitted events, and an empty
   process-launcher command list - mirroring the existing
   `anInvalidCustomSelectionNeverSavesARunOrEmitsAnyEvent` test's own shape) and its companion
   `allowsAPublicSubmissionUnderThePortfolioProfile` (proves the same `PORTFOLIO` policy still lets
   a `PUBLIC` run reach a launched process) to `RunServiceTest`.
2. **[P2] The production image was permissive (`LOCAL_DEV`) unless Compose explicitly overrode it**
   - `deploy/runner-service/Dockerfile` now sets `ENV RUNNER_DEPLOYMENTPROFILE=PORTFOLIO` itself
   (fail-closed), with `docker-compose.yml` setting the identical value again as deliberate
   belt-and-braces documentation rather than the only thing enforcing it. **Verified live**: built
   the image, ran it directly with `docker run` and zero Compose environment override at all -
   `GET /api/v1/capabilities` still returned `PUBLIC` only.
3. **[P2] No init/reaper for the process-spawning container** - `runner-service` now sets
   `init: true` in `docker-compose.yml` (Docker's own minimal init, tini, becomes PID 1). **Verified
   live inside the container** (`docker exec ... ps aux`, not just host-visible processes): PID 1 is
   `/sbin/docker-init`, `java` is PID 7; submitted a real `PUBLIC`/`SMOKE` run through to `SUCCEEDED`
   and confirmed a clean process table afterward (no leftover Gradle/JUnit/Chromium processes);
   submitted a second run and cancelled it mid-flight while it was genuinely running (`ps aux`
   confirmed the real process tree at that moment: the `gradlew` wrapper process, its Gradle daemon
   child with the new `-Xmx512m` cap already visible on the daemon JVM's own command line, live) -
   after cancellation completed (`status: CANCELLED`), the process table was clean again, no
   zombies or orphans. A dedicated `TIMED_OUT` repro was not additionally engineered: `RunService`
   routes both cancellation and timeout through the identical `terminateWithinLifecycleGate`/
   `processLauncher.terminate()` code path, so the cancellation check above already exercises the
   code this fix targets.
4. **[P2] Deployment docs still described the pre-Round-3 state** - §1's TLS bullet and the §5 D1
   task list both still said `SITE_ADDRESS` wasn't passed through/no ports published/no persistent
   volumes (all now done), and the task list additionally still showed the buggy `${SITE_ADDRESS:-}`
   form as if it were the real fix. Corrected both to describe the actual bare-variable-reference
   approach and point at this round's own writeup for why the distinction matters.
5. **[P3] `CapabilitiesResponse`'s own Javadoc still referenced `#current()`** (no-arg) - updated to
   `#current(RunAvailabilityPolicy)`.
6. **[P3] The Dockerfile's header comment still called this a "D0 spike image"** - reworded to
   "Production image for runner-service (Faza D1 ...)".
7. **[P3, explicitly deferred, not a D1 blocker]** - base image versions (`eclipse-temurin:21-jdk-
   jammy`, `node:24-slim`, `caddy:2-alpine`, `postgres:17-alpine`) are still moving tags, not pinned
   to an exact version+digest. Tracked in "Still open after D1" below, to be done no later than D5.

Verified after all fixes: `./gradlew.bat spotlessApply test :runner-contract:test
:runner-listener:test :runner-service:test` green (`RunServiceTest` now 30/30, including the two new
portfolio-boundary tests). Docker: rebuilt `deploy-runner-service` fresh, confirmed the fail-closed
default via a bare `docker run` (point 2 above), then brought up `runner-service`+`postgres` via
`docker-compose.debug.yml`'s direct loopback port (bypassing `web`/Caddy entirely, since this
round's checks only needed `runner-service` itself and the host's port `80` was still legitimately
held by this machine's own pre-existing local RBP stack) for the `init: true` process-tree checks
(point 3 above). Cleaned up fully afterward the same way as Round 3: `docker compose down -v`, both
images removed, the throwaway `.env` deleted, confirmed via `docker ps -a`/`docker images`/
`docker volume ls` that nothing from this round survives.

**Still open after D1**: a real domain to actually exercise TLS issuance end to end (`SITE_ADDRESS`
has only been verified unset, i.e. plain `:80` - D5 buys the domain); re-running the RAM/disk
measurement in §5 now that heap caps actually exist and once D2's persistence path is active, before
the D5 purchase; D2 (Postgres) and D3 (auth on launch/cancel) remain fully unimplemented, per the
schema/protocol and security-boundary sections above. **Explicitly deferred, not a D1 blocker per
this round's own review** (backlog, revisit no later than D5): every base image
(`eclipse-temurin:21-jdk-jammy`, `node:24-slim`, `caddy:2-alpine`, `postgres:17-alpine`) is pinned
to a moving tag, not an exact version+digest - two builds of the identical commit are not
guaranteed to produce an identical deployment today. Pin each to `image:x.y.z@sha256:...` before
D5, ideally with Renovate/Dependabot opened against the digest so updates stay a reviewed PR rather
than a silent drift.
