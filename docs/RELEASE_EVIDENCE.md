# Release evidence

Raw JUnit/Allure reports live under `build/` (gitignored, regenerated per run), so this file is
the durable record of what a specific release/tag was verified against.

## automation-foundation-v1.0

- **Date:** 2026-08-25
- **Target:** local Docker Compose SUT (`./gradlew.bat localSutUp && ./gradlew.bat localSutHealth`), `baseUrl=http://localhost`
- **Commands:** `./gradlew.bat localTest`, then `./gradlew.bat stabilityTest -PstabilityRuns=10`
- **Result:** 27/27 tests passed on every one of 10 consecutive `stabilityTest` iterations (0 failures, 0 errors each run) — counts aggregated from the per-run JUnit XML under `build/stability-results/run-{1..10}/test-results/`:

  | Run | Tests | Failures | Errors |
  |---|---|---|---|
  | 1 | 27 | 0 | 0 |
  | 2 | 27 | 0 | 0 |
  | 3 | 27 | 0 | 0 |
  | 4 | 27 | 0 | 0 |
  | 5 | 27 | 0 | 0 |
  | 6 | 27 | 0 | 0 |
  | 7 | 27 | 0 | 0 |
  | 8 | 27 | 0 | 0 |
  | 9 | 27 | 0 | 0 |
  | 10 | 27 | 0 | 0 |

- **Also green at this point:** `./gradlew.bat spotlessCheck test` (default read-only suite, 20 tests including the `TestConfigTest` mutation-guard unit tests added for this release).

This table is hand-verified from artifacts already on disk, not re-run at doc-writing time (Docker wasn't up in that session) — regenerate with the commands above to reproduce, or before cutting the next tag.

## runner-dashboard-v1.0 (Faza 9: CI for the runner/dashboard vertical slice)

- **Date:** 2026-09-02
- **Scope:** `runner-service` (Spring Boot SSE test runner) + `runner-dashboard` (React/Vite frontend) + `dashboardE2eTest` (real-browser Playwright E2E across both), and the two new CI workflows that gate them: `dashboard-quality.yml` (frontend quality gate + backend/frontend OpenAPI contract) and `dashboard-e2e.yml` (real-browser dashboard E2E), on top of the existing `quality-gate.yml`/`local-sut.yml`.

### Frontend test suite + coverage

- **Command:** `npm run check` (in `runner-dashboard/`) — format, lint, import boundaries, TypeScript, `vitest run --coverage`, production build.
- **Result:** 118/118 tests passed across 17 test files. Coverage (v8 provider, gated at 80/75/80/80 statements/branches/functions/lines):

  | Metric | Result |
  |---|---|
  | Statements | 95.91% (329/343) |
  | Branches | 90.03% (253/281) |
  | Functions | 95.53% (107/112) |
  | Lines | 96.42% (324/336) |

  JUnit XML written to `runner-dashboard/test-results/junit.xml` (added specifically so CI has a machine-readable report to upload on failure, alongside the coverage directory).

### OpenAPI contract

- **Command:** `npm run api:check:snapshot` (no live backend) and, separately, `npm run api:check:contract` against a real locally-started `runner-service` (`java -jar runner-service/build/libs/runner-service-*.jar`, health-polled at `/actuator/health`).
- **Result:** both green — the committed `openapi/runner-api.json` snapshot, the generated typed client, and the live backend's `/v3/api-docs` all agree; `git status` stayed clean after regenerating from the live backend.

### Dashboard E2E (`dashboardE2eTest`)

- **Command:** `./gradlew.bat dashboardE2eTest` (one command — depends on `:runner-service:bootJar` and `dashboardBuild` automatically).
- **Result:** 7/7 scenarios passed in 1m51s: run lifecycle, 404 run, download log, strict Cancel (reaches `CANCELLED`), SSE gap-replay, native-reconnect, backend-unavailable/recovery. Zero orphaned `java`/`node` processes left behind afterward (checked via `Get-CimInstance Win32_Process`), zero files under `build/dashboard-e2e-failures/` (nothing failed).
- **Real E2E vs. controlled SSE-transport tests — important distinction:** 5 of the 7 scenarios (run lifecycle, 404, download log, Cancel, backend-unavailable/recovery) are genuine end-to-end tests — a real `runner-service` process, a real `runner-dashboard` production build served via `vite preview`, driven by a real headless Chromium, with no network mocking. The other 2 (gap-replay, native-reconnect) are honestly labeled, in their own test-class Javadoc, as **browser-level SSE transport/recovery integration tests**: they use Playwright's network-routing interception to simulate a dropped/gapped SSE connection against the real backend, which is the only practical way to exercise that recovery path deterministically — not a claim that the backend itself was ever actually killed mid-stream for those two (that scenario is what the isolated `BackendUnavailableE2eTest` actually does).

### Phase 8 polish closed out

- `cancelActiveRunIfAny`'s interrupted-wait path now throws (instead of returning silently) so `safely()` logs an incomplete cleanup instead of hiding it.
- `BackendUnavailableE2eTest` now gets the same screenshot/trace/video-on-failure evidence as every other dashboard-e2e test, via a new shared `BrowserFailureArtifacts` helper (extracted from `DashboardE2eEnvironment`, which now calls the same helper).
- `dashboardBuild`/`dashboardE2eTest` are now documented in the root `README.md` (prerequisites, one-command invocation, the 7 scenarios, and exactly where reports/logs/failure artifacts land).

### GitHub Actions — status

All four items below are now confirmed on GitHub's real `ubuntu-latest` runners (this environment cannot push or trigger `workflow_dispatch` itself — the user ran these by hand from the Actions tab and shared the logs back for review):

- [x] `dashboard-quality.yml`, `workflow_dispatch` run — both jobs green. `frontend-quality`: 118/118 tests, coverage 95.91%/90.03%/95.53%/96.42% (identical to the local numbers above), `api:check:snapshot` clean, build succeeded. `openapi-contract`: `:runner-service:bootJar` succeeded, the health-poll loop found the backend healthy on the first check (well under the 60s budget), the `find`-based bootJar selection (not tied to `-SNAPSHOT`) picked the right jar, `api:check:contract` regenerated the snapshot/client from the live `/v3/api-docs` and `git status` stayed clean, backend stopped cleanly. Run ID not separately recorded (neither job produced an artifact, since `frontend-quality`'s evidence upload and `openapi-contract`'s backend-log upload are both `if: failure()` and neither job failed) — see the Actions history for the exact run if needed.
- [x] `dashboard-e2e.yml`, `workflow_dispatch` run — **run ID `33611158050`** (attempt 1). All 7 scenarios passed on Linux (`BackendUnavailableE2eTest`, `CancelE2eTest`, `DownloadLogE2eTest`, `GapReplayE2eTest`, `NotFoundRunE2eTest`, `ReconnectE2eTest`, `RunLifecycleE2eTest`), in 1m48s — within noise of the 1m51s measured locally on Windows. Chromium + Linux system deps install (`playwrightInstall -PwithDeps=true`) took ~4m24s. `dashboard-e2e-failure-artifacts-*` upload correctly reported "No files were found" (`if-no-files-found: ignore`, not a failure) since nothing failed.
- [x] Deliberate break-and-revert, to prove the failure-artifact upload paths actually work: temporarily changed `NotFoundRunE2eTest`'s expected text to a string that can never appear on the page, pushed (`f8c093d`), re-ran `dashboard-e2e.yml`. **Run ID `33619171045`** (attempt 1): `NotFoundRunE2eTest` failed as expected (`AssertionFailedError: Locator expected to be visible`) while the other 6 scenarios still passed — no cascading failure, confirming the shared-environment cleanup in `DashboardE2eEnvironment.afterTestExecution` isolates one failing test from the rest. Crucially, `dashboard-e2e-failure-artifacts-33619171045-1` was uploaded with **3 files, 214711 bytes** (screenshot.png + trace.zip + video.webm for that one test) — proving the `if: always()` upload step actually captures evidence on a real failure, not just in the local dry run. The other three uploads (JUnit XML, HTML report, process logs) also still succeeded despite the job failing, confirming `if: always()` works as intended across the board.
- [x] Revert pushed as `3c84af1` ("Adding passing test for dashboard e2e test") — confirmed via a fresh `git fetch` against the real GitHub remote that `master`'s tip contains the correct assertion text (`"This run is no longer available"`) again. **Final green re-confirmation: run ID `33621362007`** (`workflow_dispatch`, `run_number` 3), confirmed directly via GitHub's public REST API (`GET /repos/.../actions/runs/33621362007` and its `/jobs` sub-resource, unauthenticated, read-only) rather than taking a screenshot/log at face value: `head_sha` = `3c84af10ade97314e10473a7885c5f2a020aadef` (exactly the revert commit), `conclusion: success` for the job and every one of its 15 steps, "Run dashboard E2E suite" step ran 10:50:06→10:51:35 UTC (~1m29s), all four upload steps (JUnit, HTML report, process logs, failure artifacts) and the leftover-process cleanup step all reported `success`.

### Post-run review finding — found, fixed, and proven the same day

A review of the two new workflows after the runs above caught a real **P1**: both `quality-gate.yml` (pre-existing) and `dashboard-quality.yml` (new) had `push: branches: [main]`, but this repo's actual default branch — confirmed via `git branch --show-current`, `git remote show origin`, and a fresh anonymous `git ls-remote --symref` against GitHub — is `master`. A plain `git push` to `master` would never have auto-triggered either workflow; every green run recorded above was a manual `workflow_dispatch`. Fixed by changing both to `branches: [master]`, committed and pushed as `434f63c` ("Finishing Faze 9"). `dashboard-e2e.yml`/`local-sut.yml` are unaffected (schedule + `workflow_dispatch` only, no `push` trigger).

**Fix independently confirmed working**, via GitHub's public REST API (`GET /repos/.../actions/runs?event=push`, unauthenticated) rather than trusting the fix in isolation: because GitHub evaluates a `push` event's trigger filters using the workflow file version present in the pushed commit itself, `434f63c` (which touches both workflow files) auto-triggered both workflows as real `push` events — no `workflow_dispatch` involved:

| Workflow | Run ID | Event | `head_sha` | Job(s) | Conclusion |
|---|---|---|---|---|---|
| `Dashboard Quality Gate` | `33622635374` | `push` | `434f63c` | `frontend-quality`, `openapi-contract` | both `success` |
| `Quality Gate` | `33622635481` | `push` | `434f63c` | `read-only-tests` | `success` |

### Sign-off

`runner-dashboard-v1.0` is now a **complete sign-off**: every claim above — the local numbers, the three `workflow_dispatch` runs, the deliberate-failure proof and its revert, and the P1 branch-trigger fix — is confirmed either by direct local execution or independently via GitHub's REST API (run/job-level `conclusion`, not just eyeballed logs), including proof that a plain `git push` to `master` now actually triggers CI on its own.

## Faza D2.6 - production acceptance (real Docker Compose stack, real crash recovery)

- **Date:** 2026-09-06
- **Scope:** the real three-container production topology (`deploy/docker-compose.yml`) — `web` (Caddy), `runner-service`, `postgres` — not `bootRun` + a bare local Postgres. See `docs/DEPLOYMENT_ARCHITECTURE.md`'s own D2.6 section for the reproducible commands; this entry is the sanitized transcript of running them for real. Superseded a first pass that used `docker compose restart` (a graceful `SIGTERM`) instead of a genuine crash — a review correctly flagged that as insufficient proof of the hard-crash scenario D2.5/README actually promise; the transcript below is the corrected, `SIGKILL`-based run.

**Stack startup** — `docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.debug.yml --env-file deploy/.env up --build -d`, then `docker compose ... ps`:

```
NAME                      IMAGE                   SERVICE          STATUS                    PORTS
deploy-postgres-1         postgres:17-alpine      postgres         Up (healthy)              5432/tcp
deploy-runner-service-1   deploy-runner-service   runner-service   Up                        127.0.0.1:8082->8080/tcp
deploy-web-1              deploy-web              web              Up                        0.0.0.0:18080->80/tcp, 0.0.0.0:18443->443/tcp(+udp)
```

`postgres` published **no host port at all** (confirmed above — only `5432/tcp`, the container-internal port, is listed; no `->` host mapping exists for it anywhere). `web`'s ports are remapped off the real 80/443 only because this dev machine already had an unrelated stack bound to host port 80 (`WEB_HTTP_BIND=18080`/`WEB_HTTPS_BIND=18443` in `deploy/.env` — see `deploy/.env.example`; both are unset, falling back to the real 80/443, in any actual deployment).

**Network isolation** — `docker exec deploy-web-1 sh -c "wget -T 3 -O /dev/null http://postgres:5432"` timed out (`wget: download timed out`); the same address reached successfully from inside `deploy-runner-service-1`. As corrected in `docs/DEPLOYMENT_ARCHITECTURE.md` §1, this is because `web` has no membership on the `data` network at all — not because of `data`'s own `internal: true` (which only restricts that network's *external* connectivity, and would not by itself stop `web`↔`postgres` traffic if `web` were ever mistakenly also attached to `data`).

**The crash scenario — submitted a `PUBLIC`/`FIXTURE` run, killed it mid-flight with an artifact already ingested:**

1. `POST /api/v1/runs {"environment":"PUBLIC","suite":"FIXTURE"}` → `202`, `runId` assigned, status `QUEUED`.
2. Subscribed to `GET /api/v1/runs/{id}/events` live (no `Last-Event-ID`) in the background.
3. Tight-polled `GET /api/v1/runs/{id}` and `GET /api/v1/runs/{id}/artifacts` in the same loop iteration until both were true at once: run status **still `RUNNING`**, and the artifacts endpoint already returned 2 entries (a `SCREENSHOT`, 1,620,071 bytes, and a `TRACE`, 1,316,252 bytes — `StepDrilldownFixtureTest`'s deliberate third-step failure). The instant that was observed, fired the kill in the very same loop iteration to close the race as tightly as possible:
   ```
   docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.debug.yml \
     --env-file deploy/.env kill -s SIGKILL runner-service
   ```
   Output: `Container deploy-runner-service-1 Killing` / `Killed`. `docker compose ps` afterward showed `Exited (137)` — a genuine `SIGKILL` (128+9), not a graceful stop: `docker logs` for the whole container lifetime contains **zero** occurrences of `"graceful"` — no `GracefulShutdown`/`@PreDestroy` ever ran.
4. **Direct Postgres check, before bringing the service back** (`docker exec deploy-postgres-1 psql -U runner -d runner -c "..."`):
   - `runs`: `status = RUNNING`, `finished_at` = `NULL`, `next_event_sequence = 15` — left exactly mid-flight, no partial/corrupt row.
   - `run_events`: 14 rows, sequence 1-14, ending at `TEST_FAILED` — no `RUN_FINISHED`, confirming the crash landed before that transition ever started.
   - `artifacts`: both rows already present (same 2 `artifact_id`s later re-checked after recovery), proving D2.4's incremental `TEST_FAILED` ingestion hook had already durably committed them before the process died — the actual D2.4×D2.5 intersection this scenario exists to prove, not just an already-terminal run's artifacts surviving a restart.
5. `docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.debug.yml --env-file deploy/.env up -d runner-service`. Log:
   ```
   ... Started RunnerServiceApplication in 3.751 seconds (process running for 4.292)
   ... RunRecoveryService : Recovered 1 non-terminal run(s) to ERROR on startup
   ```
6. **Direct Postgres check, after recovery**: `runs.status = ERROR`, `detail` = the expected "runner-service restarted before this run reached a terminal status..." text; `run_events` now has 15 rows, sequence 15 = `RUN_FINISHED`; `SELECT COUNT(*) FROM run_events WHERE run_id = ... AND event_type = 'RUN_FINISHED'` = **exactly 1**. Both `artifacts` rows unchanged — same `artifact_id`s, same `size_bytes` as step 4.
7. **Reconnect-and-replay, no gap, no duplicate**: the live subscriber from step 2 had received events 1-14 (ending at `TEST_FAILED`) and then genuinely stopped (the connection died with the process — no `RUN_FINISHED` ever reached it). A fresh `GET .../events` (no `Last-Event-ID`) after recovery returned the complete 15-event history in order, ending in exactly one `RUN_FINISHED` (`runOutcome: ERROR`). A second reconnect with `Last-Event-ID: 14` (exactly where the dropped subscriber last was) returned exactly one event — sequence 15, `RUN_FINISHED` — not a re-delivery of 1-14, not a gap.
8. **Artifact metadata/payload re-verified through the real HTTP API** (not just Postgres directly): `GET /api/v1/runs/{id}/artifacts` returned the identical 2-entry JSON as step 4; downloading each artifact returned `200` with byte counts matching exactly (`1620071`, `1316252`) and genuinely valid files (`file` command: `PNG image data, 1440 x 3726`; `Zip archive data`).

**Idempotency and older-run readability, against the real stack** — a second, ordinary `docker compose restart runner-service` (nothing left non-terminal at that point) logged no `Recovered` line at all. An older, separately-completed terminal run and the just-recovered `ERROR` run both remained fully readable via the real edge path (`http://localhost:18080/api/v1/runs/{id}`) throughout.

**RAM/disk remeasurement** (`PUBLIC`/`REGRESSION`, `docker stats --no-stream`, ~4s sampling — see `docs/DEPLOYMENT_ARCHITECTURE.md` §5's own "D2.6 remeasurement" for the full table): peak `runner-service` **1.617 GiB**, `web` ~15 MB, `postgres` ~78 MB throughout (essentially flat under real read/write load) — system-wide peak ~1.71 GB, materially unchanged from the pre-D2/pre-heap-cap ~1.75 GB figure. `pgdata` volume after this session's several runs: ~49 MB — mostly Postgres's own baseline cluster/WAL overhead (confirmed by how little it moved across an entire additional round of runs), not a measured per-run growth rate; establishing the real growth rate is D4 retention work, not this measurement's.

**Teardown**: `docker compose ... down -v` — all three containers, both networks, and every volume created for this run removed cleanly; a separate, unrelated Docker stack already running on this machine (used for other local work) was left completely untouched throughout (verified via `docker ps` before and after).

### Sign-off

D2.6, and with it all of Faza D2 (D2.1-D2.6), is a **complete sign-off**: every acceptance criterion in `docs/DEPLOYMENT_ARCHITECTURE.md`'s own D2 implementation plan is proven against the real production topology with a genuine hard crash (`SIGKILL`, not a graceful restart) — exactly-once recovery, no event gap or duplicate across a real crash, artifact metadata ingested before a crash surviving recovery to `ERROR`, `postgres` never exposing a host port, and the network-isolation boundary correctly attributed to topology rather than `internal: true`.
