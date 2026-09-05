# D0 — Deployment architecture spike (2026-09-05)

> **Superseded by [`docs/DEPLOYMENT_ARCHITECTURE.md`](DEPLOYMENT_ARCHITECTURE.md).** This document
> is the first, narrower D0 pass (bare-metal RAM measurement methodology, no Docker artifacts yet)
> - kept as a historical record of that measurement, not a living architecture reference. §1's own
> "what this means for D1" recommendation below (Spring Boot serving the dashboard's static files
> directly) was **superseded and reversed** once the user locked the final topology: Caddy serves
> the React build directly instead (no static resources baked into the `runner-service` jar at
> all) - see `DEPLOYMENT_ARCHITECTURE.md`'s §1 for the decision, and its `deploy/` directory for the
> real, built-and-run Dockerfiles/Compose files this document only speculated about.

Faza D's first step, per the user's own scoping: a short investigation - same-origin dashboard/
API/SSE, how the Gradle/Playwright child processes actually get launched in a packaged deployment,
and a real (not guessed) RAM estimate - before any Dockerfile gets written. No code changes; every
finding below was checked against the real running system (a live `runner-service` + real suite
runs launched through its own REST API, not just read from source), consistent with this project's
standing discipline.

## 1. Same-origin dashboard/API/SSE

**Already decided, and already built for, by the dashboard's own existing code** - this wasn't a
decision D0 needed to make from scratch. `runner-dashboard/vite.config.ts`'s own comment states it
directly: *"The frontend only ever calls relative URLs (`/api/...`, `/actuator/...`) - proxying
here means dev has no CORS configuration to maintain, and matches the same-origin production
deployment (Spring Boot serving both the API and these static files) this app is ultimately built
for."* Confirmed by grepping the frontend source: no absolute `http://localhost:8080` URLs, no
`VITE_API_*` base-URL env var, nothing CORS-related anywhere in `runner-service` either (no
`CorsConfiguration`/`@CrossOrigin`/`WebMvcConfigurer` - grepped, none exist).

**~~What this means for D1~~ (superseded - see the notice at the top of this document)**: this
section originally recommended building the dashboard's production bundle and having Spring Boot
serve it as static resources directly (one JVM, one process, one origin). The user's own
`docs/DEPLOYMENT_ARCHITECTURE.md` §1 locked a different, better answer instead: Caddy serves the
React build directly (a discarded Node build stage, a plain `caddy:2-alpine` runtime image with
`dist/` copied in - no static resources in the `runner-service` jar at all), which keeps the
frontend and backend independently deployable while still being same-origin and adding no extra
container (Caddy is already required for TLS regardless). The same-origin *fact* below (the
frontend already only calling relative URLs, no CORS config anywhere) is still accurate and is
exactly what makes the Caddy-based approach work with zero frontend code changes either way.

## 2. How Gradle/Playwright processes actually launch

Read `SuiteCommandFactory.commandFor` directly (not assumed): every launched run runs

```
<repoRoot>/gradlew(.bat) <task> --rerun --no-daemon -Drunner.runId=<id> -Drunner.rawEventsDir=<dir>
```

`--no-daemon` is deliberate and already documented in that class's own Javadoc: a daemon build hands
work off to a long-lived background JVM that `GradleProcessRunner.terminate()` killing the process
tree would not reliably reach, breaking cancellation/timeout guarantees. **Consequence for
packaging**: there is no persistent Gradle daemon to rely on or worry about across runs in
production - every run is a genuinely fresh, from-cold Gradle invocation, at the cost of not
reusing a warm daemon's build cache/model between runs (a real, accepted tradeoff for correctness,
not something D1 should try to "fix" by dropping `--no-daemon`).

This also means the container running `runner-service` needs far more than just the built jar - it
needs the **entire repository present on disk** at whatever path `RunnerProperties.repoRoot()`
resolves to: the Gradle wrapper (`gradlew`, `gradle/wrapper/`), every `build.gradle`/
`settings.gradle`, and the full `src/test/java` tree (plus `src/dashboardE2eTest` if that ever needs
to run in the deployed environment, which it doesn't for the public-facing portfolio suites).
Concretely, the image/build needs, baked in ahead of time rather than fetched on first real
request:

- JDK 21 (both for `runner-service` itself and for every child Gradle build it launches).
- The Gradle wrapper's distribution pre-downloaded (`gradle-9.6.0-bin` et al.) and the dependency
  cache pre-populated (e.g. `RUN ./gradlew --version` and a throwaway `RUN ./gradlew test
  --dry-run`-style warm-up during image build) - a cold Gradle wrapper otherwise downloads its
  ~100MB+ distribution over the network on the very first real user-triggered run, which is slow
  and a bad first impression for a portfolio demo.
- Node 24 + the Playwright-managed Chromium build (`./gradlew playwrightInstall`) pre-installed -
  same reasoning, avoids a slow/flaky first run.
- The dashboard's own production build - can be produced once at image-build time (`npm run
  build`), no Node needed at runtime for serving it, only for the test-time Playwright driver.

**Scoping recommendation carried into D1/D5**: only expose `PUBLIC`-environment suites (`SMOKE`,
`API`, `UI`, `JOURNEY`, `REGRESSION`, `FIXTURE`) on the public portfolio deployment. `LOCAL`
requires the full 7-container local RBP Docker Compose stack running alongside everything else
(per `infra/rbp/README.md` and this repo's own convention that the runner never starts/stops that
stack itself) - that's a second, much heavier deployment concern which doesn't fit a small
portfolio VPS budget and isn't needed to demonstrate the runner/dashboard system publicly (the
`FIXTURE` suite already gives the full step/failure/artifact drill-down on demand, without Docker).

## 3. Real measured RAM (not estimated) - methodology

Docker Desktop was off for this measurement, so all numbers below are `PUBLIC`-only, matching the
scoping recommendation above. Measured on this Windows dev machine (JDK 21, Gradle 9.6.0) via
`Get-Process`'s `PrivateMemorySize64`, correlating each process back to its real command line (not
guessed) - `Win32_Process.CommandLine` filtered for `--no-daemon`, `-Dallure.results.directory`
(the forked JUnit worker's own marker), and `ms-playwright` (the real Chromium binaries Playwright
installs, not this session's own browser-automation Chrome tabs, which were excluded by path).
Three real runs were launched through the actual `POST /api/v1/runs` endpoint - the same path the
dashboard itself uses - not a bare `./gradlew` invocation from a terminal, so these numbers reflect
what a real dashboard-triggered run actually costs, including the process tree `RunService`/
`SuiteCommandFactory` really launches.

| Process | Role | Measured private memory |
|---|---|---|
| `runner-service` (Spring Boot app itself, run via `./gradlew bootRun` for this measurement) | Always-on backend | ~470 MB |
| `gradlew ... --no-daemon` (the run's own top-level build process) | Per-run coordinator | ~180-185 MB, stable across a fast `SMOKE` run and a slower `LOCAL`/`JOURNEY` run |
| Forked JUnit worker JVM (`-Dallure.results.directory=...`) | Runs the actual test/Playwright code | ~455-500 MB |
| Chromium (`chromium_headless_shell`, ~8-10 OS sub-processes per logical browser instance - GPU/network/renderer/utility, normal multi-process Chromium architecture) | One active browser | ~330 MB total, one instance at a time (`maxParallelForks = 1`, already pinned in `build.gradle`) |

**A real, actionable finding, not just numbers**: none of these JVMs have an explicit heap cap
today. `gradle.properties`' `org.gradle.jvmargs=-Xmx1g` only applies to daemon-mode builds - it
does **not** bound the `--no-daemon` build process or the forked Test JVM, both of which are
sizing themselves via plain JVM ergonomics (roughly a fraction of whatever RAM the host happens to
have). On a developer workstation with plenty of RAM this is invisible; on a small VPS, several
independently-ergonomic-sized JVMs competing for memory with no coordination between them is a real
OOM-kill risk. **Recommended for D1** (not implemented now - D0 is investigation only): an explicit
`-Xmx` on `runner-service`'s own launch command (e.g. `256m` - it is a control-plane REST+SSE app,
not a data-heavy service), an explicit `maxHeapSize` on every `Test` task in `build.gradle` (e.g.
`512m`), and an explicit `-Xmx` passed to the per-run `--no-daemon` child process via its
environment (`JAVA_OPTS`/`GRADLE_OPTS`, wired through `ProcessLauncher.start`'s existing
`environment` map).

**Also worth carrying into D1**: hosting the backend via `./gradlew bootRun` (as this measurement
did, matching current dev practice) itself costs an extra ~180 MB (Gradle wrapper client) + ~330-
420 MB (the daemon `bootRun` spins up, separate from anything test-run-related) purely to keep the
*always-on* service alive - overhead a real deployment shouldn't pay. Package and run the built jar
directly (`java -jar runner-service-<version>.jar`, or the Spring Boot Gradle plugin's own
`bootBuildImage`) instead of `bootRun` for the always-on process.

### Estimated realistic peak, with the D1 caps above applied

~256 MB (backend, capped) + ~200 MB (no-daemon build coordinator, roughly matches today's
unforced ~180 MB) + ~512 MB (forked test JVM, capped) + ~330 MB (Chromium, one instance) + ~250 MB
(minimal Linux VPS OS/kernel/sshd baseline) ≈ **1.5-1.8 GB peak during an active run**, comfortably
below 2 GB with the caps in place; noticeably tighter (and a real risk of OOM under load) without
them, since today's unbounded-ergonomics numbers alone already sum past 1.7 GB before any OS
overhead is even counted.

## 4. Sizing recommendation for D5

**At least 2 GB RAM**, not 1 GB - a 1 GB VPS leaves essentially no headroom once the backend,
one active suite run's build process, its forked test JVM, and one real Chromium instance are all
counted, even with the explicit heap caps D1 should add. This is a technical finding about what the
system actually needs, not a recommendation for any specific provider - the user's own $15-20/month
budget and whichever VPS they've already bought/chosen decides the rest; this spike only answers
"how much RAM does it actually need," which most mainstream providers' ~$12-20/month tier already
covers at 2 GB or more.

## Verified

All process-tree/memory numbers above came from real, live measurement on this machine, not
estimated from source alone: started a real `runner-service` via `bootRun`, launched three real
runs through its own `POST /api/v1/runs` endpoint (`PUBLIC`/`SMOKE`, `LOCAL`/`JOURNEY` twice, one
specifically timed to catch the process tree mid-run), and read back each process's own
`CommandLine` via `Get-CimInstance Win32_Process` to correctly attribute every JVM/Chromium process
to its real role rather than guessing from process name alone. Confirmed via `./gradlew --status`
and direct command-line inspection that the `--no-daemon` per-run build process is genuinely
separate from, and does not spin up, a persistent daemon - the persistent daemon seen during
measurement was attributable specifically to this session's own `bootRun` invocation (which does
not pass `--no-daemon`), not to anything `RunService` itself launches. All ad-hoc processes started
for this spike were cleaned up afterward (`./gradlew --stop`, confirmed port 8080 free and no
surviving `java.exe`/`chromium_headless_shell.exe`).

**This document's own "next" is `docs/DEPLOYMENT_ARCHITECTURE.md`** - the real Dockerfiles/Compose
topology, the corrected (higher) RAM measurement against an actual container, and the locked D1
task list all live there now, not here.
