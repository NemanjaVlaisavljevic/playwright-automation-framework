# Local Restful Booker Platform stack

Everything here integrates the upstream [restful-booker-platform](https://github.com/mwinteringham/restful-booker-platform) application locally through Docker Compose. We do not maintain a fork or write our own Dockerfiles — we pin an exact upstream commit and build its own `docker-compose.yml` as-is, with a small override for explicit, defensive configuration.

- `upstream.properties` — the pinned `repository` and `commit`. Bump `commit` deliberately to move to a newer upstream revision.
- `compose.override.yml` — layered on top of upstream's `docker-compose.yml` via `docker compose -f ... -f ...`. Not a fix (the Dockerfiles already default this wiring correctly); makes the inter-service configuration explicit instead of implicit.
- `patches/assets-build-env.patch` — a small, versioned patch applied to the pinned checkout by `localSutPrepare` (see "Known limitation" below for why it's needed). `git apply --check` fails loudly if a future re-pin of `commit` no longer matches its context lines, rather than applying it somewhere wrong.

## Gradle tasks

| Task | What it does |
|---|---|
| `localSutPrepare` | Fetches the pinned commit into `build/rbp-sut/<commit>` and applies `patches/assets-build-env.patch` on top, committing the result locally (idempotent; skips if already present and patched) |
| `localSutBuild` | Builds the 6 Java service jars (`auth`, `booking`, `room`, `report`, `branding`, `message`) inside a throwaway `maven:3.9-eclipse-temurin-26` container. Upstream's own Dockerfiles are copy-only — they `COPY target/*-exec.jar`, they do not build it — so this step has to run first. `assets` needs nothing extra; its own Dockerfile builds it with `npm` in a multi-stage build. |
| `localSutUp` | Runs `localSutBuild`, then builds and starts all 7 services via `docker compose -f <upstream>/docker-compose.yml -f compose.override.yml up -d --build` |
| `localSutHealth` | Depends on `localSutUp`. Polls every Java service's Spring Boot Actuator health endpoint (`http://localhost:<port>/<service>/actuator/health`) from the host until all report `UP`, then checks the `assets` front door itself (`GET /`, `GET /api/room/`, `GET /api/room/1`) — the actuator checks alone don't catch a proxy-only failure like the one below. No fixed blind startup delay (it polls on a short interval against a deadline instead); on any failure it dumps `compose ps`/`compose logs` (via the same logic as `localSutDiagnostics`, see below) before failing, to save a manual re-run for diagnosis. |
| `localSutVerifyRunning` | The same health/front-door check as `localSutHealth`, sharing its actual check logic, but with **no** `dependsOn` on `localSutUp`/`localSutBuild`/`localSutPrepare` at all and much shorter timeouts (10s/10s vs. 5min/2min) — it never starts, stops, or rebuilds the stack, it only fails fast and clearly if the stack isn't already up. Intended as a precondition for `localJourneyTest` (and, via that, for the runner-service dashboard's own `LOCAL` environment), not for interactive/CI use where `localSutHealth`'s startup tolerance is what you actually want. On a failed check it still calls the same read-only `compose ps`/`logs` diagnostics `localSutHealth` does — that is a real Docker call, just never one that mutates the stack's lifecycle. |
| `localSutDiagnostics` | Dumps `docker compose ps`/`logs` for the running stack to `build/diagnostics/rbp/` (and the console). Deliberately does **not** depend on `localSutUp`/`localSutBuild` — safe to run any time, including right after a CI failure, without rebuilding or restarting the stack it's diagnosing. |
| `localSutDown` | Stops the stack |
| `localSutReset` | Stops the stack and removes its containers/volumes (scoped to the `rbp-local-sut` compose project only) |
| `localTest` | Depends on `localSutHealth` (which transitively brings up and builds the stack), then runs the full regression suite (including `mutation`) against `http://localhost` |
| `localJourneyTest` | Depends on `localSutVerifyRunning` (**not** `localSutHealth`/`localSutUp` - assumes the stack is already up), runs the `journey` suite (read-only **and** `mutation`, unlike the public `journeyTest` task) against `http://localhost`. This is what the runner-service dashboard's `LOCAL` environment actually launches for `Suite.JOURNEY` - see "Dashboard `LOCAL` runs" below. |
| `stabilityTest` | Brings the stack up once (via `localSutHealth`), then reruns just the test execution N times (`-PstabilityRuns=N`, default 10) against that same running stack, retaining each run's reports under `build/stability-results/run-<n>/`. Runs every iteration regardless of earlier failures, then reports a pass/fail summary and fails overall if any run failed. |

Requires Docker Desktop (with Compose v2) and `git` on the machine running these tasks. The default `./gradlew test` never touches Docker or this stack — running against the local SUT is always an explicit, separate step.

## Single-origin routing

All API/UI traffic goes through one origin, `http://localhost` (port 80, the `assets` service), matching how the public host is shaped. `assets/next.config.js` rewrites `/api/room/:path*` &rarr; the internal `rbp-room` service, and the same pattern for `booking`, `auth`, `report`, `branding`, and `message` — see the `rewrites()` block in that file in the fetched source. This is upstream's own mechanism; nothing extra was added on our side for it. Each Java service's `server.servlet.context-path` is `/<service>` (e.g. `/room`), which is also why the Actuator health paths above are `/<service>/actuator/health` rather than bare `/actuator/health`.

### Known limitation (fixed locally via a patch): `assets` bakes in the wrong API hostnames at build time (pinned commit `d36bd3f8`)

Root cause, confirmed by inspecting the running container, not assumed: `assets/next.config.js` reads `process.env.ROOM_API` (and the 5 other `*_API` vars) at **module-load time** — i.e. whenever `next.config.js` is evaluated, which happens during `npm run build` in the Dockerfile's `builder` stage. Upstream's own `Dockerfile` only sets those `ENV` vars in the later `runner` stage, *after* the build already ran. So at build time every `*_API` var is unset and falls back to its `http://localhost:<port>` default, and `next.config.js`'s `rewrites()` destinations get compiled into the build output using that wrong host — confirmed via `docker exec` into the running `assets` container: `required-server-files.json`'s baked config and the container's live logs (`Failed to proxy http://localhost:3001/room/1 Error: connect ECONNREFUSED 127.0.0.1:3001`) both show the stale `localhost` destination, even though the container's actual runtime environment correctly has `ROOM_API=http://rbp-room:3001`. Some routes appeared unaffected by coincidence of how they're implemented, not because the underlying bug is path-shape-specific — don't read anything into which routes happened to still 200.

**Fix applied**: `infra/rbp/patches/assets-build-env.patch`, applied by `localSutPrepare`, adds the same 6 `ENV` lines to the `builder` stage (before `RUN npm run build`) so `next.config.js` sees the correct values when it matters. This is a deployment/build-ordering correction, not a change to any business logic — see the patch file's own comment for the same explanation inline in the Dockerfile. It is intentionally a versioned patch rather than a fork: `git apply --check` fails the build if a future re-pin of `commit` no longer matches its context, forcing a conscious update instead of silent drift.

`localSutHealth`'s front-door checks (`GET /`, `GET /api/room/`, `GET /api/room/1`) exist specifically because the 6 actuator endpoints alone stayed green throughout this — they only prove each Java service is up, not that `assets`' proxy can actually reach it.

**Verified fixed**: with the patch applied, `localSutHealth`'s front door check passes (`GET /api/room/1` returns `200` through the proxy), and `localTest` passes 27/27. `stabilityTest -PstabilityRuns=10` has passed 10/10.

### Resolved: the one remaining failure was a wrong test assertion, not a race

The last failure after the patch above (a room-count mismatch between the API and the rendered UI) was first suspected to be a test-isolation race — `junit-platform.properties` runs test classes concurrently, and `mutation`-tagged tests aren't serialized against concurrently-running `read-only` tests observing the same shared room inventory, which sounded like a plausible cause. It wasn't: the homepage (`assets/src/components/home/Availability.tsx`) deliberately does `rooms.slice(0, 3)` — it only ever features the first 3 rooms, by design. The test (`InventoryParityTest`, since renamed `FeaturedRoomParityTest`) was asserting every API room must appear in the UI, which was simply the wrong expectation. Fixed the assertion (`HomePage.assertBookableRooms(List<Room>)`) to check the first-3 subset — count and exact `/reservation/{roomId}` path per room, in order — instead of the full inventory. No locking-model change was needed or made; there was no race to fix.

## Dashboard `LOCAL` runs

The runner-service dashboard's `Environment` dropdown offers `LOCAL` alongside `PUBLIC`, mapped to
`Suite.JOURNEY` only (see `RunCatalog`). Unlike `PUBLIC`, a `LOCAL` run includes `mutation`-tagged
journey tests (`BookingJourneyTest` and friends) - safely, because it writes only to this local
stack, never the shared public target (`TestConfig#targetsSharedEnvironment()` naturally returns
`false` for `http://localhost`, so `AutomationExtension`'s mutation guard passes with no
`allowMutationAgainstSharedTarget` opt-in needed).

**The runner-service never starts, stops, or manages this stack itself** - `localJourneyTest` only
depends on `localSutVerifyRunning`, a fast health check with no `dependsOn` on `localSutUp`. A
developer must bring the stack up by hand first. The full flow:

1. `./gradlew.bat localSutHealth` - depends on `localSutUp`, so this one command fetches, builds,
   and starts all 7 containers, then actually waits/polls until every service reports healthy before
   returning (see the task table above). Slow (several minutes) the first time; the pinned commit's
   checkout and Java jars are cached afterward. Plain `localSutUp` on its own only starts the
   containers and returns immediately - it does not wait for them to become healthy, so running it
   alone and moving straight to step 4 risks the fail-fast outcome described there for no reason.
2. Start `runner-service` and the dashboard (`./gradlew.bat :runner-service:bootRun`, and
   `npm run dev`/`npm run preview` in `runner-dashboard/`, or the packaged app once Faza D ships).
3. In the dashboard, select `LOCAL` in the Environment dropdown - the Suite dropdown automatically
   narrows to `JOURNEY` (there is no other `LOCAL` combination yet).
4. Click **Run**. This launches `localJourneyTest` as a child Gradle process. Its own
   `localSutVerifyRunning` precondition checks the stack's health first - **if the stack isn't
   actually up (or not yet healthy), the run reaches a real Gradle process but fails fast at that
   precondition, ending as a `FAILED` run whose process log names exactly which services weren't
   healthy** (the same message `localSutVerifyRunning` would print on the command line) - this is
   the expected, correct outcome if step 1 was skipped or the stack hasn't finished starting yet, not
   a bug to chase.
5. `./gradlew.bat localSutDown` (or `localSutReset` to also drop volumes) once done - the runner
   never does this for you either.

## Public-host mutation guard

`AutomationExtension` refuses to run any `mutation`-tagged test when the configured `baseUrl` targets the same origin as `TestConfig#sharedTargetBaseUrl()` (defaults to the public host, `https://automationintesting.online`), unless `allowMutationAgainstSharedTarget` / `ALLOW_MUTATION_AGAINST_SHARED_TARGET` is explicitly set. The comparison normalizes both URLs to scheme + host + effective port (`TestConfig#targetsSharedEnvironment()`) rather than comparing raw strings, so an equivalent spelling like an explicit default port (`https://automationintesting.online:443`) can't slip past it. The comparison lives in `core` (app-agnostic — it only compares two configured URLs) while the actual public-host default lives in `config`'s `TestConfig`, consistent with `baseUrl` itself already defaulting there. `localTest` points `baseUrl` at `http://localhost`, so the guard does not need to be touched for local runs.
