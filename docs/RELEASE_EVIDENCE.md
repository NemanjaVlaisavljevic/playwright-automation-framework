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

## Faza D3.2 - admin authentication (GitHub OAuth2 Login)

- **Date:** 2026-09-07
- **Scope:** real `./gradlew.bat :runner-service:bootRun` (not the full Compose stack — no public domain/real GitHub OAuth App exists yet to register a production callback against; see "Deferred to D5" below) against a real local `postgres:17-alpine` (`localPostgresUp`), with `RUNNER_SECURITY_GITHUB_CLIENT_ID`/`_SECRET` set to placeholder values and `RUNNER_SECURITY_ADMIN_GITHUB_ID` set to a real numeric value, sufficient to exercise every authorization rule and the fail-closed startup gate for real. A genuine GitHub login round trip requires the manual GitHub OAuth App registration step the plan calls for — not something this session could do — so that specific click-through is **not** included below; everything else is verified against the real running application, not reasoned about.

**No credentials configured (today's behavior, unchanged)** — `bootRun` with no `RUNNER_SECURITY_*` env vars set:
```
GET /actuator/health          -> {"groups":["liveness","readiness"],"status":"UP"}
POST /api/v1/runs (anonymous) -> 202
```

**Fail-closed startup, `PORTFOLIO` profile, credentials missing** — `RUNNER_DEPLOYMENTPROFILE=PORTFOLIO ./gradlew.bat :runner-service:bootRun`, no `RUNNER_SECURITY_*` set:
```
java.lang.IllegalStateException: runner.deployment-profile=PORTFOLIO requires RUNNER_SECURITY_GITHUB_CLIENT_ID and
RUNNER_SECURITY_GITHUB_CLIENT_SECRET to be set - refusing to accept traffic; the process will exit so the container
orchestrator restarts it once they are configured.
BUILD FAILED
```

**Fail-closed startup, malformed admin ID** — `RUNNER_DEPLOYMENTPROFILE=PORTFOLIO RUNNER_SECURITY_GITHUB_CLIENT_ID=fake-id RUNNER_SECURITY_GITHUB_CLIENT_SECRET=fake-secret RUNNER_SECURITY_ADMIN_GITHUB_ID=not-a-number ./gradlew.bat :runner-service:bootRun` — aborts even earlier than the validator, at `AdminGithubAllowlist`'s own `@Bean` construction, before Tomcat ever starts listening:
```
Caused by: java.lang.IllegalArgumentException: RUNNER_SECURITY_ADMIN_GITHUB_ID contains a non-numeric entry: "not-a-number"
BUILD FAILED
```

**Access matrix, GitHub OAuth2 enabled** (`RUNNER_SECURITY_GITHUB_CLIENT_ID=fake-id RUNNER_SECURITY_GITHUB_CLIENT_SECRET=fake-secret RUNNER_SECURITY_ADMIN_GITHUB_ID=123456789 ./gradlew.bat :runner-service:bootRun`, real HTTP calls via `curl`):

| Request | Result |
|---|---|
| Anonymous `GET /api/v1/runs` | `200` (public, unchanged) |
| Anonymous `GET /api/v1/auth/me` | `200`, `{"authenticationRequired":true,"canManageRuns":false,"authenticated":false}` |
| Anonymous `GET /api/v1/auth/csrf` | `204`, `Set-Cookie: XSRF-TOKEN=...` |
| Anonymous `POST /api/v1/runs`, no CSRF token | `403` (CSRF filter, before authorization even runs) |
| Anonymous `POST /api/v1/runs`, valid CSRF token, no session | `401`, `{"title":"Unauthorized","status":401,"detail":"Authentication is required to perform this action.","instance":"/api/v1/runs"}` |
| `GET /api/v1/auth/oauth2/authorization/github` | `302` (starts the real GitHub redirect) |
| Unlisted route under the namespace, `GET /api/v1/auth/nope` | `401` (`anyRequest().denyAll()`, correctly routed to the entry point for an anonymous caller) |
| `GET /actuator/info` | `200` (permitted at the Spring Security layer; the production Caddy edge still only proxies `/actuator/health` publicly - an edge-level restriction, not an app-level one) |
| `GET /v3/api-docs` | `200` (permitted so `npm run api:export`/`api:check:contract` keep working once OAuth2 is enabled locally - never proxied publicly by Caddy either way) |

**MockMvc access-matrix suite** (`SecurityAccessMatrixTest`, real `SecurityFilterChain`/`GithubOAuth2UserService`/CSRF beans wired via `@Import` into a `@WebMvcTest` slice, `@WithMockUser`/`@WithMockUser(roles="ADMIN")` simulating the two authenticated states): 12/12 green, covering anonymous/non-admin/admin × create/cancel, missing-CSRF, the default-deny catch-all, confirming `ROLE_ADMIN` does not bypass the pre-existing `RunAvailabilityPolicy`/`Environment.LOCAL` rejection, and the forwarded-header scheme/host test below. `AdminGithubAllowlistTest` (7/7), `GithubOAuth2UserServiceTest` (5/5, a fake delegate - no real GitHub HTTP call), and `CurrentUserControllerTest` (3/3, plain unit tests) cover the allowlist-parsing, numeric-id-mapping, and permissive/anonymous/admin response-shape logic directly.

### Review round (2026-09-07) - 3 P1 + 2 P2, all fixed and reverified against the real running system

1. **[P1] The local dashboard silently went fully read-only.** The first pass conflated "authenticated" with "can manage runs" - with no GitHub OAuth2 configured (the permissive chain: default local `bootRun`, `dashboardE2eTest`), `/api/v1/auth/me` reported `isAdmin: false`, disabling every Run/Cancel control and pointing a "Log in with GitHub" link at an OAuth2 endpoint that does not exist in that mode. Fixed by splitting the response into `canManageRuns` (the one field every control now gates on - `true` in the permissive chain regardless of "who is logged in", since nobody is ever authenticated as anyone there) and `authenticationRequired` (hides the login control entirely when there is no login concept to offer). `CurrentUserControllerTest` (3 new unit tests) proves all three real outcomes (permissive/anonymous-with-oauth2/authenticated-admin) directly. **Re-ran the full `dashboardE2eTest` suite (20/20 green)** - including `RunLifecycleE2eTest`, `CustomRunE2eTest`, and both `CancelE2eTest` scenarios, exactly the launch/cancel flows this bug had broken.
2. **[P1] The fail-closed check ran after Tomcat had already opened its listening socket.** The original `ApplicationRunner`-based validator only executes after the application context fully refreshes - a misconfigured `PORTFOLIO` instance briefly served real anonymous traffic through the permissive chain, on every restart, before that runner got a chance to throw. Moved the check into `RunnerSecurityEnvironmentPostProcessor` itself (ordered right after `ConfigDataEnvironmentPostProcessor`), which runs during environment preparation - before `SpringApplication.run` even creates the `ApplicationContext`, so no bean, no web server factory, no listening socket ever exists. The now-redundant `RunnerSecurityEnvironmentValidator`/`ApplicationRunner` was removed. **New acceptance test `RunnerSecurityFailFastTest`** runs the real `RunnerServiceApplication` via `SpringApplication.run` with a misconfigured `PORTFOLIO` profile, asserts the expected exception, and then binds a plain `ServerSocket` to the exact port the instance was told to use - proving the port was never touched. Live-verified too: the same misconfigured `bootRun` now fails in ~1 second with zero `"Tomcat started"`/`"Started RunnerServiceApplication"` log lines at all (previously it took several seconds and Tomcat's own startup banner appeared first).
3. **[P1] A production OAuth2 callback behind Caddy's TLS termination could resolve the wrong HTTP scheme.** Without `server.forward-headers-strategy`, Spring Security's `{baseUrl}` resolution sees the internal plain-HTTP hop between Caddy and this service, not the real public `https://` origin - producing a `redirect_uri` that never matches the one registered with the GitHub OAuth App. Added `ENV SERVER_FORWARD_HEADERS_STRATEGY=framework` to `deploy/runner-service/Dockerfile` (Caddy's `reverse_proxy` already sets `X-Forwarded-Proto`/`X-Forwarded-Host` by default; safe only because `runner-service` is never reachable except through Caddy). **New test `oauth2LoginRedirectUsesTheForwardedHttpsSchemeAndHost`** registers a `ForwardedHeaderFilter` ahead of the security chain in `SecurityAccessMatrixTest`'s own `MockMvc`, sends `X-Forwarded-Proto: https`/`X-Forwarded-Host: example.com`, and asserts the real GitHub-bound redirect's `redirect_uri` is `https://example.com/...` - confirmed failing (`http://localhost/...`) before the Dockerfile-equivalent filter ordering was in place, passing after.
4. **[P2] 401/403 responses did not match the existing `ProblemDetail` contract.** `ProblemDetailAuthenticationEntryPoint`/`ProblemDetailAccessDeniedHandler` used their own throwaway `new ObjectMapper()` and never set `instance`. Fixed: both now take the application's real managed `ObjectMapper` (`JacksonConfig`'s bean) via constructor injection, and set `instance` to the request URI, matching `RunExceptionHandler`'s existing contract exactly. Live-verified: `{"title":"Unauthorized","status":401,"detail":"...","instance":"/api/v1/runs"}`.
5. **[P2] `GET /v3/api-docs` was denied once OAuth2 was enabled**, since the default-deny list didn't allowlist it - breaking `npm run api:export`/`api:check:contract` against a local `bootRun` with GitHub credentials configured. Permitted explicitly (never proxied publicly by Caddy regardless, so no new exposure). Live-verified: `200` under the OAuth2-enabled chain.

All fixes verified together: `./gradlew.bat spotlessCheck test --rerun` (every module) green, `npm run check` (289/289 tests, coverage 97.08%/94.27%/97.56%/97.26% - well above the 80/75/80/80 gate) green, and the full `dashboardE2eTest` suite (20/20, real backend + real dashboard + real Chromium) green.

**Frontend**: `npm run check` green — 287/287 tests (including new `AuthControls.test.tsx`/`auth-api.test.ts`/`problem-detail.test.ts` cases for the login link, the logged-in admin display, CSRF priming on mount and after logout, and the shared 401/403 message helper), coverage 97.08%/94.3%/97.56%/97.26% (statements/branches/functions/lines - well above the 80/75/80/80 gate), production build succeeds.

**Backend**: `./gradlew.bat spotlessCheck test --rerun` (full default suite, public target, every module) green.

### Review round 2 (2026-09-07) - 1 P1 + 3 P2 + 1 P3, all resolved and reverified against the real running system

1. **[P1] The `PORTFOLIO` profile check compared the raw `runner.deployment-profile` string against the literal `"PORTFOLIO"`, which could disagree with `RunAvailabilityConfig`'s own `@Value`-based enum conversion** (case-insensitive - a lowercase `portfolio` would silently bypass the fail-closed check entirely while still resolving to `DeploymentProfile.PORTFOLIO` later, once the real bean was created) - a security decision must not depend on whether a later, independent config binding happens to agree with an early, hand-rolled string comparison. Fixed by binding the same way Spring Boot's own configuration binding does: `Binder.get(environment).bind("runner.deployment-profile", DeploymentProfile.class)`, so this check can never disagree with what the application actually ends up running as. While fixing this, also added the missing companion check the same review flagged: `PORTFOLIO` now additionally requires a `Secure` session cookie (`server.servlet.session.cookie.secure`), refusing to start otherwise - catching a local Compose acceptance override (`SESSION_COOKIE_SECURE=false`, see point 3 below) left in place by mistake. **`RunnerSecurityFailFastTest`** (3/3) now covers both spellings (`"PORTFOLIO"`/`"portfolio"`) for the missing-credentials case and a new non-Secure-cookie case, each proving via a real `SpringApplication.run` + a plain `ServerSocket` bind that the port was never touched. Live-verified: `RUNNER_DEPLOYMENTPROFILE=portfolio` (lowercase) now fails closed identically to the uppercase spelling, in both cases with zero `"Tomcat started"` log lines.
2. **[P2] `GET /api/v1/auth/me` derived `canManageRuns`/admin status from the principal merely being an `OAuth2User`, not from the real `ROLE_ADMIN` authority Spring Security itself grants.** Today this indirect invariant happens to hold (`GithubOAuth2UserService` never produces a non-admin session), but the endpoint's own contract should describe the actual authorization rule, not rely on a fact about a completely different class never drifting. Fixed: `CurrentUserController` now explicitly checks `authentication.getAuthorities()` for `ROLE_ADMIN` and a new `CurrentUserResponse.authenticatedNonAdmin(...)` factory covers the (currently unreachable, but now explicitly correct) authenticated-without-permission case. **New regression test** `oauth2EnabledAuthenticatedNonAdminCannotManageRuns` (`CurrentUserControllerTest`, now 5/5) proves a `ROLE_USER`-only principal is reported as authenticated but unable to manage runs, not silently treated as admin.
3. **[P2] CSRF priming had no failure/recovery path** - `AuthControls`'s original `useEffect(() => void primeCsrfToken())` fired once on mount and never retried, so a login/mutation attempted while the backend was briefly unreachable at bootstrap would keep failing indefinitely even after the backend recovered, with no signal to the user or the rest of the UI. Fixed by wrapping `primeCsrfToken` in a proper query (`useCsrfReady`, `features/auth/useCsrfReady.ts`) using the exact same error-only `refetchInterval`/`refetchIntervalInBackground: true` idiom `RunLaunchForm`'s own capabilities-retry already established, and a new combined `useCanManageRuns()` hook (`features/auth/useCanManageRuns.ts`) that every admin-gated control (`RunLaunchForm`, `RunsTable`, `RunDetailsPage`) now calls instead of reading `currentUser.data.canManageRuns` directly - a logged-in admin whose CSRF token isn't primed yet can no longer see an enabled Run/Cancel button that would just 403 downstream. The logout mutation now invalidates the shared `queryKeys.csrf` cache entry (forcing every mounted consumer to re-prime for the new anonymous session) instead of calling `primeCsrfToken()` directly from one component alone. **New regression test** in `RunLaunchForm.test.tsx` (`"disables submit while CSRF priming is failing, then recovers and sends a valid X-XSRF-TOKEN header on submit"`) proves the full path end to end: priming fails -> submit disabled -> priming recovers on its own -> submit enabled -> the actual `POST /api/v1/runs` request carries the real `X-XSRF-TOKEN` header value read from the (simulated) cookie. Caught one real bug while writing this: TanStack Query rejects a queryFn resolving to `undefined` (`primeCsrfToken(): Promise<void>`) as an error - `useCsrfReady`'s queryFn now explicitly resolves `true` on success.
4. **[P2] The Secure session cookie requirement had no local-acceptance override**, so a Compose-based non-TLS acceptance run (no `SITE_ADDRESS`/domain yet) could never actually persist a login session at all - browsers refuse to send a `Secure` cookie to a non-HTTPS origin. Added `SESSION_COOKIE_SECURE` to `deploy/docker-compose.yml` (`SERVER_SERVLET_SESSION_COOKIE_SECURE=${SESSION_COOKIE_SECURE:-true}`, same escape-hatch shape as the existing `WEB_HTTP_BIND`/`WEB_HTTPS_BIND` pattern) and documented it in `deploy/.env.example`, explicit that it must be paired with a non-`PORTFOLIO` `RUNNER_DEPLOYMENTPROFILE` override for such a run - `PORTFOLIO` itself stays fail-closed and Secure regardless of this override (point 1's new check enforces exactly that). Verified via `docker compose -f deploy/docker-compose.yml --env-file deploy/.env.example config`: resolves to `SERVER_SERVLET_SESSION_COOKIE_SECURE: "true"` with no override present, confirming the default stays fail-closed.
5. **[P3] Whether the explicit `read:user` GitHub OAuth scope is even necessary - resolved during the manual click-through below, with a real surprise along the way.** GitHub's own scopes documentation confirms an OAuth app with no scope at all already gets read-only access to public profile info - exactly the three fields this service reads (`id`, `login`, `avatar_url`) - so removing the explicit `scope` property was tried first. The real click-through's authorization URL still requested `read:user` regardless: traced to Spring Security's own `CommonOAuth2Provider.GITHUB` preset, which sets `scope("read:user")` as its own default the moment the "github" registration id is auto-detected - confirmed by reading `ClientRegistration.Builder.scope()` directly, whose `if (scope != null && scope.length > 0)` guard makes an empty/absent override a deliberate no-op, not a clear. The only way to truly request zero scope would be a fully custom (non-`"github"`) provider block with hand-specified authorization/token/user-info URIs, bypassing that preset entirely - meaningful config complexity/risk for a purely cosmetic reduction in GitHub's consent screen, given `read:user` is already GitHub's own documented read-only, non-sensitive scope. Conclusion: kept `read:user` explicit (it changes nothing about what is actually requested, only documents it), reverted the brief attempt to remove it, and closed P3 with this verified answer instead of the originally-assumed one.

All fixes verified together: `./gradlew.bat spotlessApply spotlessCheck test --rerun` (every module) green - including one real ripple fix this round surfaced, `CapabilitiesControllerPortfolioProfileTest` needed the new `server.servlet.session.cookie.secure=true` property added to its own `@TestPropertySource` once the Secure-cookie fail-closed check applied to its narrow slice too. `npm run check` (290/290 tests, coverage 97.21%/94.71%/97.59%/97.4% - statements/branches/functions/lines, above the 80/75/80/80 gate) green, production build succeeds. The full `dashboardE2eTest` suite re-ran green again (20/20, real backend + real production dashboard bundle + real Chromium) as the final proof that none of this round's tightened checks broke the local dev/demo flow.

### Manual local acceptance click-through (2026-09-07) - D3.2's last open item, now closed

Real local GitHub OAuth App registered (`Runner Dashboard Local`, homepage `http://127.0.0.1:5173`, callback `http://127.0.0.1:5173/api/v1/auth/oauth2/callback/github`), `runner-service/.env.local` filled in by the user (client id/secret/admin id never seen or requested by this session), `./gradlew.bat :runner-service:bootRun` (real Postgres via `localPostgresUp`) and `npm run dev` run together, exercised in a real browser end to end.

**One real bug found along the way**: Vite's dev server defaulted to binding only the IPv6 loopback (`::1`), so `http://127.0.0.1:5173` - the exact origin the OAuth callback was registered against - was refused, confirmed independently from both this environment and a native `Invoke-WebRequest` on Windows itself. Not a D3.2 code defect (nothing in this phase's own files sets `server.host`), worked around for this session by starting Vite with `--host 127.0.0.1` explicitly; `vite.config.ts` itself was left unchanged, since a different developer's OS/network setup may not hit the same default-binding behavior.

**Results, verified via the real REST API after the click-through, not just observed in the browser**:
- A `CUSTOM` run launched as the admin: `GET /api/v1/runs/{id}` -> `"status":"SUCCEEDED"`, `"exitCode":0`.
- A `REGRESSION` run launched and then cancelled as the admin: `GET /api/v1/runs/{id}` -> `"status":"CANCELLED"`, `"exitCode":1`, `"detail":"Run was cancelled; raw event stream is incomplete because the process was interrupted before the listener closed it"` - the expected cancellation shape, not a crash.
- Backend log across the entire session: zero `WARN`/`ERROR`/exception lines related to OAuth/login/logout (the one pre-existing `WARN` is the unrelated SpringDoc startup notice, present on every `bootRun`).
- Logout confirmed to actually end the session, not just update the UI: `GET /api/v1/auth/me` immediately after -> `{"authenticationRequired":true,"canManageRuns":false,"authenticated":false}`.

This closes every remaining item of D3.2's own acceptance checklist. Full Caddy-fronted production click-through with a real domain remains D5's job, per the D5 acceptance checklist already locked in `docs/DEPLOYMENT_ARCHITECTURE.md` - no public domain/TLS exists yet to register a real production callback against.

## Faza D3.3 - abuse protection

**Date:** 2026-09-07. **Scope:** a per-resource-surface rate-limit matrix (never one global limit), a verified reverse-proxy IP-trust boundary, a pre-deserialization request-body size cap reinforced by Bean Validation, CSP/Permissions-Policy, and an explicitly proven CORS-unsupported decision. Plan reviewed and revised once before implementation - see the plan's own "Context" section for the resulting five design constraints; the review's specific corrections (per-surface limits not a global one, the anonymous SSE/download/read surface, a verified IP-trust boundary, pre-deserialization body limits, and a narrower CSP) are all reflected in what was actually built, not just planned.

### Rate-limit matrix, implemented and tested

| Surface | Route(s) | Limit | Key | Verified |
|---|---|---|---|---|
| OAuth authorization | `GET /api/v1/auth/oauth2/authorization/github` | 5/min | client IP | Real `bootRun`: 6th attempt within a minute → real `429` + `Retry-After: 59`; recovered to `302` again after waiting out the window |
| OAuth callback | `GET /api/v1/auth/oauth2/callback/github` | 10/min | client IP | Real `bootRun`: 11th attempt → real `429` |
| Create run | `POST /api/v1/runs` | 3/min **and** 10/hour | GitHub numeric admin ID | `SecurityAccessMatrixTest` (real `OAuth2User` principal, not `@WithMockUser` - see why below) |
| Cancel run | `POST /api/v1/runs/*/cancel` | 10/min | GitHub numeric admin ID | Covered by the same `AbuseRateLimitFilter` matrix entry as create-run; wiring identical |
| Public REST GET | `/api/v1/runs`, `/api/v1/runs/*`, `/api/v1/capabilities`, `/api/v1/tests` | 120/min | client IP | `SecurityAccessMatrixTest`: 121st call → `429` |
| Log/artifact download | `/api/v1/runs/*/log`, `/api/v1/runs/*/artifacts`, `/api/v1/runs/*/artifacts/*` | 30/min | client IP | Same filter/matrix entry as public-read; wiring identical |
| SSE | `GET /api/v1/runs/*/events` | 3 concurrent connections per IP, on top of the existing global `sseMaxSubscribers` | client IP | `RunEventStreamControllerTest`: 4th concurrent connection from one IP → `429` |
| Health check | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` (D4.3.1 added the latter two - see that section) | unlimited | — | Not matched by any rule in the filter, by construction |
| Metrics scrape | `/actuator/prometheus` (D4.3.2 - see that section) | unlimited | — | Same reasoning as health check - proven by `PrometheusEndpointIsNotRateLimitedTest`, not merely assumed |

`runner.queue-capacity` (existing, unchanged) is explicitly documented as *not* a rate limit - it bounds queued runs, never call frequency.

**A real bug found only by live verification, not caught by the automated test suite**: the first implementation registered `AbuseRateLimitFilter` via `.addFilterAfter(filter, AuthorizationFilter.class)`, reasoning that 401/403 should always win over rate-limiting. Live-testing the OAuth-authorization surface against a real `bootRun` showed the 6th attempt in a minute still returned `302`, never `429` - traced to Spring Security's own filter ordering: `OAuth2AuthorizationRequestRedirectFilter`/`OAuth2LoginAuthenticationFilter` (which actually handle those two routes) run, and fully commit their response, well before `AuthorizationFilter` - a filter registered after it is simply never reached for those two routes at all. Fixed by registering after `SecurityContextHolderFilter` instead (still early enough to catch the OAuth routes, still late enough that an already-authenticated admin's session `Authentication` is available for the numeric-id key extraction) - re-verified live: 6th OAuth-authorization attempt now correctly returns `429` + `Retry-After: 59`, and the 11th OAuth-callback attempt returns `429` too. **This is exactly why the automated `@WebMvcTest` suite alone did not catch it**: no test had been written yet for the OAuth-authorization/callback surfaces specifically (only create-run and public-read were covered) - the gap in test coverage, not just the bug itself, is the real lesson.

**Recovery after a limit expires - proven live, not assumed**: after the OAuth-authorization 429, waited 65 real seconds (past the 1-minute window) and confirmed the exact same request succeeded again (`302`), proving the fixed-window counter actually resets rather than latching permanently.

### Reverse-proxy IP-trust boundary

`deploy/runner-service/Dockerfile`'s `SERVER_FORWARD_HEADERS_STRATEGY` changed from D3.2's `framework` to `native` - activates Tomcat's own `RemoteIpValve` (`server.tomcat.remoteip.internal-proxies`, defaults already covering every private/Docker-internal range) instead of a plain `ForwardedHeaderFilter`, which has no trusted-proxy concept at all. Confirmed via reading Spring Boot 4's own `TomcatWebServerFactoryCustomizer` source directly (not assumed from documentation) that `native` is required to get `RemoteIpValve` at all - `framework` only ever activates the generic filter. `RemoteIpValve` is a Tomcat connector-level `Valve`, so `MockMvc` (which dispatches directly to `DispatcherServlet`, never booting a real embedded Tomcat) cannot exercise it.

**Verified against a real `docker compose` stack** (`deploy/docker-compose.yml` - Caddy + `runner-service` + Postgres, fake OAuth2 credentials matching this project's own established fake-creds precedent, never a real GitHub round trip): each request in a run of six sent a *different*, deliberately spoofed `X-Forwarded-For` value straight at Caddy - the 6th still hit the configured 5/min OAuth-authorization limit exactly as if every request had come from the same real client, proving Caddy's own default `X-Forwarded-For` handling overrides a client-supplied value rather than relaying it. Repeated for the 120/min public-read limit with a *different* spoofed value on every single one of 121 requests: the 121st still returned `429`, at the exact expected count - the real client identity resolved through Caddy → `RemoteIpValve` → `AbuseRateLimitFilter` stayed the same one real key throughout, regardless of what any individual request tried to claim.

One real, separate finding along the way, unrelated to the trust boundary itself: with the local `WEB_HTTP_BIND=18080` host-port-remap escape hatch in use (this machine's own port 80 was already occupied by an unrelated stack), the OAuth `redirect_uri` Caddy/Spring resolved together omitted that remapped port entirely (`http://localhost/...`, not `http://localhost:18080/...`) - even when explicitly supplying a spoofed `X-Forwarded-Host` with a port, confirming Caddy overrides that too rather than relaying it. Consistent with Caddy's own documented `{http.request.host}` placeholder (used for its default `X-Forwarded-Host` value) never carrying port information at all, regardless of what port the original request actually used. This has no effect on real production, which always terminates on the real domain's standard `80`/`443` (no port ever needed in the URL there) - it is only visible when deliberately remapping the local escape-hatch port, and is noted here for whoever next uses that escape hatch, not treated as a D3.3 defect.

### Request body size, before deserialization

`RequestBodySizeLimitFilter` (registered in both chains - a resource-protection concern, not auth-adjacent) checks `Content-Length` up front and wraps the request `InputStream` in a byte-counting wrapper as a second, independent layer. Live-verified against a real `bootRun`: a `16385`-byte body → real `413` `ProblemDetail`; a normal small body proceeds to the existing CSRF check exactly as before (`403` for a missing token, not `413`) - confirming the size filter doesn't interfere with legitimate requests. `CreateRunRequest` also gained `@Size(max = 25)` on `testKeys` and `@Size(max = 200)` per key, verified via `RunControllerTest` to produce a clean `400` independently of `CustomTestSelectionValidator`'s own identical cap (defense in depth, not a replacement).

**Two defaults verified empirically, not assumed, per this project's own discipline**: (1) Spring's built-in `@Valid`-failure handling already produces a compliant `400` `ProblemDetail` with no extra code - confirmed by a passing test, not new handling added for it. (2) Spring Boot's Jackson autoconfiguration defaults to **lenient** deserialization (unknown JSON fields silently ignored), the opposite of what was assumed - a test sending an extra field first failed against the real default, then passed once `spring.jackson.deserialization.fail-on-unknown-properties: true` was added to `application.yml`.

### CSP, Permissions-Policy, CORS

CSP/Permissions-Policy added to `deploy/web/Caddyfile`'s existing header block (not Spring Security - Caddy is the only thing that serves the SPA's own `index.html`/JS/CSS directly). Directive set verified against the real production bundle (`npm run build` + reading `dist/index.html`/CSS: no inline scripts/styles, no `data:` URIs anywhere), so neither `'unsafe-inline'` nor `data:` was added preemptively. **Verified against a real running Caddy container** (`caddy validate` first confirmed the config parses against the actual stock `caddy:2-alpine` image with no custom `xcaddy` build needed; then a live container serving a real file, `curl -D -`, confirmed the exact response headers):

```
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' https://avatars.githubusercontent.com; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'
Permissions-Policy: camera=(), microphone=(), geolocation=()
```

(plus the pre-existing `X-Content-Type-Options`/`X-Frame-Options`/`Referrer-Policy`, and confirmed the `-Server` directive still successfully removes the `Server: Caddy` header).

CORS: no `CorsConfigurationSource` bean, `.cors(CorsConfigurer::disable)` explicit on both chains - made into a stated, tested decision. `SecurityAccessMatrixTest` proves both a cross-origin `OPTIONS` preflight against a mutating endpoint and an ordinary cross-origin-shaped `GET` against a public route get no `Access-Control-Allow-Origin` header at all.

### Review round 2 (2026-09-07) - 2 P1 + 3 P2 + 1 P3, all fixed and reverified

A second, independent code-review pass (findings from direct source review - the reviewer's own Gradle in their environment could not launch child processes at all, `Unable to establish loopback connection`, so nothing here came from re-running the suite themselves) found two P1s and several P2/P3 issues the first pass's own live-verification and automated tests had not caught.

1. **[P1] `AbuseRateLimitFilter`/`RequestBodySizeLimitFilter`, as plain `Filter` beans, were also auto-registered by Spring Boot as generic servlet-container filters - on top of, and independent from, their intended manual `SecurityFilterChain` wiring.** Confirmed by reading `ServletContextInitializerBeans`: Spring Boot registers *any* `Filter` bean found in the context as a container-level filter regardless of whether that same bean is also explicitly wired via `.addFilterBefore/After(...)`. Concretely this meant `AbuseRateLimitFilter` - meant to run only in the OAuth2 chain - could also run under the permissive/local chain, at an ordering controlled by Spring Boot's own default (`FilterRegistrationBean` ordering), not by this project's own explicit chain positioning. Fixed exactly as proposed: `SecurityConfig` now also declares a `FilterRegistrationBean<AbuseRateLimitFilter>`/`FilterRegistrationBean<RequestBodySizeLimitFilter>` for each, both with `.setEnabled(false)` - suppressing the automatic generic registration entirely, leaving the explicit `SecurityFilterChain` wiring as the *only* place either filter ever runs. **New regression test** (`SecurityAccessMatrixTest`, real Spring context, not `MockMvc`-only) asserts the permissive/local chain path has zero abuse-rate-limiting behavior (many rapid requests, none ever `429`) while the OAuth2 chain path still limits identically to before.
2. **[P1] `InMemoryRateLimiter.windows` grew without bound - every new client IP left a permanent record, and a real attacker (scanner, botnet, rotating IPv6) can present many genuinely distinct addresses, not just spoofed headers a trust boundary can reject.** Rewrote the map's lifecycle: an opportunistic sweep every 256 calls removes any window whose duration has already elapsed, **plus** a hard ceiling (`maxTrackedKeys`, default 100,000) that, once reached, evicts an already-expired entry if one exists or else the single oldest-by-creation-time entry - a real upper bound on memory regardless of how many distinct keys ever appear, with no dependency on Redis or any other new component for this single-instance deployment. **New test** `trackedKeyCountStaysBoundedUnderManyOneOffKeys` feeds the limiter 500 single-use keys through a limiter constructed with a cap of 20, asserting the tracked-key count never exceeds 20.
3. **[P2] Multi-rule rate limiting (create-run's per-minute + per-hour) was not one atomic decision** - the original code checked/incremented the minute rule, then separately checked/incremented the hour rule, so a request rejected by the hourly rule had already silently consumed the minute rule's budget, and the returned `Retry-After` could reflect whichever rule happened to be checked last rather than the one that actually mattered. Rewrote `InMemoryRateLimiter.tryAcquire` to take the full list of rules for a key, lock every involved window in a single globally-consistent order (sorted by `System.identityHashCode`, via recursive `synchronized` blocks - safe against deadlock between concurrent multi-rule callers), evaluate all rules without mutating any of them, and only increment counters afterward if every rule passed; on rejection, `Retry-After` is the **largest** remaining time among the rules that actually blocked the request. Also fixed `Duration.toSeconds()` truncation (`59.9s` was rounding down to `Retry-After: 59`, advising a client to retry before the window had actually elapsed) - `AbuseRateLimitFilter.ceilSecondsAtLeastOne` now rounds any nonzero remainder up, floored at a minimum of 1. **New tests**: `multiRuleChecksAreAtomicNeverPartiallyConsumingOnRejection` and `retryAfterReflectsTheLargestRemainingTimeAmongRejectingRules` (`InMemoryRateLimiterTest`), plus three unit tests for the rounding fix itself (`AbuseRateLimitFilterTest`).
4. **[P2] The chunked/lying-`Content-Length` body path had no proven `413` behavior** - the original stream-based protection threw a plain `IOException` on overflow, and its own comment admitted the resulting HTTP status could end up `400` instead of `413`, depending on how Spring's dispatch machinery happened to translate that exception. Redesigned `RequestBodySizeLimitFilter` to read the **entire** request body into memory up front (bounded by a running byte count during the read itself, independent of any `Content-Length` header) *before* ever calling `filterChain.doFilter(...)`, throwing a dedicated `RequestBodyTooLargeException` the filter catches itself and maps to a real `413` `ProblemDetail` - then re-serves the already-validated bytes to the rest of the chain via a `ByteArrayInputStream`-backed `ServletInputStream` wrapper. This guarantees a uniform `413` regardless of whether the client declared a (possibly false) `Content-Length` at all, since the check now happens entirely before Spring's `DispatcherServlet`/deserialization layer ever sees the request. **New standalone test** `RequestBodySizeLimitFilterTest` drives the real `Filter.doFilter()` directly against hand-built Mockito mocks (`MockHttpServletRequest`'s own `getContentLengthLong()` in this Spring version is hard-derived from actual content bytes with no way to construct a genuine declared/actual mismatch through it, confirmed by reading its source) - proves a body whose *declared* length is `-1` (unreliable/absent, exactly a chunked request) but whose *actual* bytes exceed the cap still yields a real `413` with the filter chain never invoked, and that a small body under the cap with the same unreliable declared length passes through unchanged.
5. **[P2] The IP-trust boundary (`native` + `RemoteIpValve`) had been acceptance-tested for real against a live Compose stack, but the Dockerfile comment overclaimed this as covered by an automated `AbuseRateLimitFilterIntegrationTest` that does not exist.** Corrected the comment to state precisely what is and isn't true: `RemoteIpValve` is a Tomcat connector-level `Valve`, structurally unreachable by any `MockMvc`-based test, and this specific mechanism was verified only against a real `docker compose` stack (see the "Reverse-proxy IP-trust boundary" section above for the actual spoofed-`X-Forwarded-For` and 121st-request transcripts) - never simulated or asserted "by construction."
6. **[P3] `InMemoryRateLimiter.java` contained a literal NUL byte as the separator between a rate-limit namespace and its key** (not a text space character), which caused Git/`grep`/review tooling to treat the file as binary - hiding its diffs from ordinary review entirely. Confirmed via `grep -aoP` before the fix (found the byte) and after (file reports as plain "Java source, ASCII text"). Replaced the string-concatenation key with a proper typed `private record WindowKey(String namespace, String key)` used as the map key directly - no separator character of any kind needed, and the same rewrite that fixed the P1 bounded-memory issue above.

All fixes verified together: `./gradlew.bat spotlessApply spotlessCheck test --rerun` (every module) green, including the 13 new/updated tests across `InMemoryRateLimiterTest` (8), `AbuseRateLimitFilterTest` (3, new file), and `RequestBodySizeLimitFilterTest` (2, new file). Live-reverified against a real `bootRun` (with `runner-service/.env.local` temporarily moved aside to rule out its own OAuth2 credentials silently keeping the OAuth2 chain active during the permissive-chain check): the permissive/local chain sent 130 rapid requests with zero `429`s; restoring `.env.local` and repeating against the OAuth2 chain reproduced the original `429` at exactly the 121st request, with no double-counting from the registration-bean change. Re-verified against a real Compose stack as documented above (spoofed-`X-Forwarded-For` tests, the 121st-public-read-request test).

### Gates

`./gradlew.bat spotlessApply spotlessCheck test --rerun` (every module) green.

`dashboardE2eTest` (real backend + real production dashboard bundle + real Chromium): **22/22
green** on the final rerun (2026-09-07) - the closing verification round 2 explicitly asked for,
not a 19/20 or 21/22 called "close enough."

Getting there took two earlier attempts that failed on the same external cause, root-caused each
time rather than assumed: `DownloadLogE2eTest` launches a real `smokeTest` run, and that run's own
`RoomApiContractTest` failed against the live public demo site's actual room inventory - first with
`/rooms/4: required property 'image'/'description' not found`, then (a second attempt, a different
timestamp) `/rooms/3` with the identical two missing fields. Both traced via the real spawned
process's own log, not assumed from the test name alone, and both independently confirmed by
querying the live site's real `/api/room` endpoint directly at the time of each failure - a
different room id each time, consistent with some other consumer's own mutation-test runs
periodically leaving a "Test room"-shaped entry with incomplete data on this shared, mutable
third-party instance this project doesn't control. `RoomApiContractTest`/`smokeTest` never touch
`runner-service`'s own HTTP surface or any of D3.3's filters at all, so neither failure could be
attributable to D3.3 code - confirmed, not merely argued, by the fact that a third attempt (querying
`/api/room` immediately beforehand to confirm all four rooms currently carried both fields) passed
cleanly end to end, including every test that exercises D3.3's own filters through the real
dashboard UI.

## Faza D3.4 - security test coverage

**Date:** 2026-09-07. **Scope:** an audit of D3.4's own checklist against everything D3.1-D3.3 had
already incidentally covered, closing the real gaps found rather than re-testing what already had
coverage.

**Already covered by existing tests** (no new work needed): the full access matrix, anonymous/
non-admin/admin × create/cancel, missing-CSRF rejection, the whole D3.3 rate-limit matrix,
`ROLE_ADMIN` never bypassing `RunAvailabilityPolicy`, and anonymous read access - all in
`SecurityAccessMatrixTest` and its sibling security test files already documented above.

**Gaps found and closed:**

1. **A mismatched (not merely missing) CSRF token** - `authenticatedAdminCreateWithAMismatchedCsrfTokenIsForbidden`
   sends a real `XSRF-TOKEN` cookie and a deliberately different `X-XSRF-TOKEN` header value,
   proving the repository actually compares values rather than only checking presence.
2. **Session expiration behavior** - `server.servlet.session.timeout` was already set (`12h`), but
   with no documented rationale and no test of the actual behavior. Lowered to `4h` (the user's own
   call: a rarely-used single-admin session doesn't need a long idle window). **Precisely scoped,
   per a review finding**: `server.servlet.session.timeout` is an *idle* timeout under the Servlet
   `HttpSession` contract (the clock resets on every access), not an absolute session lifetime - an
   actively-used session (stolen or not) is never force-expired at the 4h mark just because 4h have
   passed since login. New test
   `anInvalidatedSessionIsTreatedAsAnonymousNeverAsLingeringAdminOrAServerError` proves only the
   *post-invalidation* half of that (`MockHttpSession.invalidate()` simulates the state right after
   invalidation happens, by whichever mechanism - explicit logout or the container's own eventual
   idle-timeout eviction; `MockMvc` has no way to simulate real wall-clock idle time actually
   elapsing, so this is not a test of the 4h clock itself): the very next request after
   invalidation is fully anonymous (`/auth/me` reports `authenticated: false`, a mutation attempt
   gets `401`, never a `500`). Deliberately not paired with a real absolute-TTL mechanism (a
   server-side authenticated-at timestamp + injectable `Clock`, checked on every request) - for
   this single-admin, `HttpOnly`+`Secure`-cookie portfolio deployment, the idle timeout is judged a
   reasonable "forgot to log out" bound, not a defense against an already-stolen active session
   (that threat is mitigated by the cookie attributes themselves, not session lifetime).
3. **`mutation`/`fixture` reachability via a raw REST call** - `MUTATION` is not even a `Suite` enum
   value (structurally unreachable, not merely policy-rejected); a new test
   (`aSuiteValueOutsideTheAllowlistedEnumIsRejectedWith400`) proves that by construction. `FIXTURE`
   *is* a legitimate, allowlisted suite (the deliberately-always-fails drill-down fixture), so three
   new tests prove it gets exactly the same authorization treatment as any other mutation
   (`anonymousFixtureLaunchIsRejectedWithAProblemDetail401`,
   `authenticatedNonAdminFixtureLaunchIsForbidden`, `authenticatedAdminFixtureLaunchSucceeds`) -
   never a client-choosable escape hatch reachable anonymously.
4. **A stale Javadoc reference** in `SecurityAccessMatrixTest` pointed at a nonexistent
   `AbuseRateLimitFilterIntegrationTest` - corrected to reference the real
   `PermissiveChainHasNoAbuseRateLimitTest`/`OAuth2ChainAppliesAbuseRateLimitTest` pair and this
   document's own D3.3 Compose-stack evidence.
5. **The real browser E2E login->launch->cancel->logout**, deliberately left manual-only at D3.2
   (see that section above) to avoid a real-GitHub-account dependency in CI, is now automated
   without reintroducing that risk. New `OAuthFlowE2eTest` (its own fully isolated
   backend+dashboard+WireMock instance, mirroring `BackendUnavailableE2eTest`'s isolation pattern)
   runs the real `runner-service` with its normal Spring Boot OAuth2 client binding -
   `spring.security.oauth2.client.provider.github.authorization-uri`/`token-uri`/`user-info-uri`/
   `user-name-attribute` point only the three endpoint URIs at a local WireMock server, the
   standard, supported way to redirect an existing `CommonOAuth2Provider.GITHUB`-derived
   registration at a different provider - deliberately **not** a fake in-test
   `ClientRegistrationRepository` (an earlier draft of this test used one; corrected during review
   so the real `RunnerSecurityEnvironmentPostProcessor` bridging and the real
   `ClientRegistrationRepository` construction are both actually exercised, not bypassed). WireMock
   stands in for exactly three GitHub endpoints: `authorize` (echoes back whatever `redirect_uri`/
   `state` Spring Security's own `OAuth2AuthorizationRequestRedirectFilter` sent, via WireMock's
   response templating - exactly what a real GitHub authorize endpoint does), `access_token`
   (returns a fixed fake bearer token), and `user` (returns one fixed fake GitHub identity, id
   `999`). Two real scenarios proven end to end through a real browser:
   - `loginLaunchCancelLogoutRoundTripsThroughTheRealOAuth2Flow`: anonymous (Run button present but
     disabled, "Admin login required") -> clicks "Log in with GitHub" -> real authorization
     redirect -> stub GitHub round trip -> real callback/token exchange/user-info call -> real
     session -> `/auth/me` confirms authenticated admin -> launches a `FIXTURE` run (CSRF-protected
     POST, succeeding proves the token was actually primed and sent) -> cancels it, reaches
     `CANCELLED` -> logs out -> `/auth/me` reverts to anonymous -> the Run button is disabled again.
     WireMock's own request log additionally verifies the token endpoint received the expected
     `code` and the user-info endpoint was called with the expected `Bearer` token.
   - `aNonAllowlistedGithubIdentityIsRejectedWithNoAdminSession`: same stub identity (id `999`),
     but this instance's own `RUNNER_SECURITY_ADMIN_GITHUB_ID` is set to a different id (`42`) -
     login must fail outright with no admin session at all (`GithubOAuth2UserService` throws before
     any `SecurityContext` is established), confirmed via `/auth/me` still reporting
     `authenticated: false`.

   Which identity is treated as admin is controlled entirely by which `RUNNER_SECURITY_ADMIN_GITHUB_ID`
   each test's own isolated backend is started with - the stub itself never changes between the two
   scenarios, so there is nothing to reconfigure or reset between tests.

All fixes verified together: `./gradlew.bat spotlessApply spotlessCheck test --rerun` (every
module) green. The full `dashboardE2eTest` suite reran green at **24/24** (the 22 from D3.3's own
closing plus the two new `OAuthFlowE2eTest` cases) - the real, non-flaky proof that both the new
WireMock-based OAuth automation and the lowered session timeout introduced no regression anywhere
else in the suite.

### Review round (2026-09-07) - 3 P1 + 1 P2, all fixed and reverified

A review of `OAuthFlowE2eTest` itself (architecture judged sound - real Spring binding, isolated
WireMock, real browser/session/CSRF flow) found three acceptance-proof gaps and one cleanup gap.

1. **[P1] The non-admin rejection scenario could pass green without the OAuth round trip ever
   completing.** It started already on a dashboard URL and waited for
   `url -> url.startsWith(DASHBOARD_BASE_URL)` - a predicate the *starting* URL already satisfies,
   so `waitForURL` returned immediately, before the click's own redirect chain had necessarily gone
   anywhere. The final anonymous state is identical to the initial one, so a token-exchange or
   user-info call that silently never happened at all would have looked exactly like a correctly-
   rejected login. **The same latent flaw existed in the happy-path test's own login wait**
   (`page.waitForURL(DASHBOARD_BASE_URL + "/runs")`, called after already being on exactly that
   URL) - not flagged directly by the review, but found and fixed for the identical reason once the
   mechanism was understood. Fixed both: replaced with `page.waitForResponse(...)` matched against
   the real `/api/v1/auth/oauth2/callback/github` response, registered *before* the click that
   triggers it (the two-arg form - a wait issued after the click would race a same-machine round
   trip fast enough to have already completed). The non-admin test additionally now resets
   `gitHubStub`'s request journal in `@BeforeEach` (`gitHubStub.resetRequests()` - the stub's
   *mappings* are class-shared, but its journal is not test-scoped by default) and verifies the
   token/user-info endpoints were actually called *during that test* - the real proof this is a
   rejection, not a round trip that silently never ran.
2. **[P1] The logout wait was a no-op race.** Logout is a plain `fetch()` with no navigation (see
   `AuthControls.tsx`), and the page was already sitting on a `/runs/{runId}` URL that already
   matched `DASHBOARD_BASE_URL + "/**"` before the click - the same `waitForURL`-resolves-instantly
   flaw as point 1, here against a URL that structurally never changes at all for this action.
   Fixed by waiting for the real `204` response from `POST /api/v1/auth/logout` (again the two-arg
   `waitForResponse` form), then asserting the "Log in with GitHub" link is visible again - the
   actual, observable proof logout completed, not an assumption from timing.
3. **[P1] The invalidated-session test's own Javadoc, and this document's original wording,
   overclaimed what the 4h `server.servlet.session.timeout` actually guarantees.** The Servlet
   `HttpSession` contract makes this an *idle* timeout - the clock resets on every access - not an
   absolute session lifetime; an actively-used session (stolen or not) is never force-expired at
   the 4h mark. Two options were on the table: build a real absolute-TTL mechanism (server-side
   authenticated-at timestamp + injectable `Clock`, checked per-request, tested with a short TTL),
   or correct the claim to match what is actually implemented and tested. **The user's own call**:
   keep the idle timeout (reasonable for this single-admin, `HttpOnly`+`Secure`-cookie portfolio
   deployment - the stolen-active-session threat is mitigated by the cookie attributes, not session
   lifetime) and fix the documentation/Javadoc instead of building the heavier mechanism. Both
   `application.yml`'s own comment and
   `anInvalidatedSessionIsTreatedAsAnonymousNeverAsLingeringAdminOrAServerError`'s Javadoc (point 2
   above) now say precisely what is tested: post-invalidation behavior (by whichever mechanism -
   logout or eventual idle-timeout eviction), never the 4h idle-timeout clock itself, which
   `MockMvc` has no way to simulate elapsing.
4. **[P2] `OAuthFlowE2eTest`'s own `@AfterEach` cleanup was not exception-safe** - `page.context()
   .close()` and `backend.stop()` ran unprotected inside a single try/finally, so either one
   throwing silently skipped a later step (a failed `context.close()` meant the failure video was
   never saved; a failed `backend.stop()` meant the temp video directory was never deleted).
   Rewritten to run each step independently (collecting and chaining any failure as a suppressed
   exception, rethrown once at the end) - the exact same pattern already used by
   `DashboardE2eEnvironment.SharedResources#close`/`BackendUnavailableE2eTest`'s own teardown
   methods, applied here for the first time to a per-test `@AfterEach` rather than only a
   class-level teardown. **A follow-up pass on this same fix found it still incomplete**:
   `BrowserFailureArtifacts#safely` only guards the individual screenshot/tracing/`Files.move`
   calls *inside* `captureBeforeClose`/`saveVideoIfFailed` - but `captureBeforeClose`'s own
   `page.context()` argument is evaluated before that method is even entered, and
   `saveVideoIfFailed`'s first statement (`page.video()`) runs before its own `safely()` block, so
   either one throwing (a real possibility once the Playwright transport itself has already
   failed) would still have skipped every later step, including `backend.stop()`. Both calls are
   now wrapped in the same collect-and-chain mechanism as `context.close()`/`backend.stop()`, not
   just the two originally covered.

All fixes verified together: both `OAuthFlowE2eTest` scenarios reran green against the real stack
after each fix, `./gradlew.bat spotlessApply compileDashboardE2eTestJava` stayed clean throughout,
and the full backend gate (`spotlessApply spotlessCheck test --rerun`) stayed green.

**`CustomRunE2eTest` stabilized as a separate hygiene fix, same session**: this suite's own
`dashboardE2eTest` baseline should not depend on the shared public demo site's own mutable room
data staying clean - `CustomRunE2eTest` exists to prove the dashboard/orchestrator's CUSTOM-suite
selection wiring, not to re-detect the same drift `RoomApiContractTest` already legitimately
detects elsewhere in the automation suite. It had been intermittently failing during these reruns
- root-caused via the real spawned process log rather than assumed: it happened to select exactly
`RoomApiContractTest` + `HomePageTest` as its "two selected public tests," and `RoomApiContractTest`
hit the same pre-existing, already-documented live external data drift as D3.3's own closing gate
(`docs/RELEASE_EVIDENCE.md`'s D3.3 "Gates" section) - a different room id each time this session
(`4`, then `3`, then, confirmed live via a direct `/api/room` query, `6`). Swapped its two selected
tests to `AuthenticationApiTest`'s "Admin can obtain a non-empty session token" and `AdminLoginTest`'s
"Admin with an invalid password stays on the login screen" - still one `API` + one `UI` test as the
class's own Javadoc requires, both read-only auth checks against fixed, deterministic credentials,
never against the site's own mutable room data. `RoomApiContractTest` itself is untouched anywhere
else - it still correctly detects this drift in the automation suite's own regular runs, which is
its actual job.

**Full `dashboardE2eTest` closing status**: both `OAuthFlowE2eTest` scenarios, `CustomRunE2eTest`
with its stabilized selection, and every other test in the suite passed. This suite's own baseline
no longer depends on the shared public demo site's data happening to be clean at the moment it
runs.

## Faza D4.1 - retention policy

**Date:** 2026-09-07. **Scope:** a bounded run-history window (age or count, whichever is hit
first), a shorter independent per-run artifact-purge protocol, and a crash-safe, idempotent,
on-demand cleanup mechanism - never touching a non-terminal run, never leaving a tombstoned run
visible through any public read path while its files are still being removed. Plan reviewed and
revised twice before implementation (see the plan's own locked-decisions list); every one of the
reviewer's corrections - `finished_at` not `requested_at` as the age basis, the deterministic
`finished_at DESC, requested_at DESC, run_id DESC` tie-break, per-run (never per-artifact) purge
windows, tombstoning as plain nullable columns rather than a new `RunStatus`, the exact
immediate-tombstone visibility contract, and the atomic-claim-before-any-irreversible-action
concurrency guard - is reflected in what was actually built, not just planned.

### Schema and configuration

New migration `V4__add_runs_retention_columns.sql` adds three nullable `TIMESTAMPTZ` columns to
`runs` (`cleanup_started_at`, `artifacts_purge_started_at`, `artifacts_purged_at`), each guarded by
a DB `CHECK` constraint that neither cleanup nor purge can ever be marked for a non-terminal run -
a real database invariant, not just application trust. Proven directly: a raw-SQL test
(`retentionColumnsCanOnlyEverBeSetForATerminalRunAtTheDatabaseLevel`) attempts to set each column
against a `RUNNING` row and confirms Postgres itself rejects it.

`runner.retention-*` config (`RunnerProperties`): `runHistoryMaxAge` (`P30D`), `runHistoryMaxCount`
(`500`), `artifactMaxAge` (`P14D`), `cleanupInterval` (`PT1H`) - every `Duration` validated positive,
count `>= 1`, and a cross-field check that `artifactMaxAge` never exceeds `runHistoryMaxAge` (a
larger artifact window would mean the purge branch could never fire before full-run cleanup already
deleted the run) - rejected as a startup configuration error, same as every other `RunnerProperties`
invariant.

### Tombstone visibility and orchestration

`RunLifecycleStore.findById`/`findAll` filter out any run with `cleanup_started_at IS NOT NULL` -
every existing caller (`RunController`, SSE replay via the same `findById`-backed lookup) then
treats a tombstoned run exactly like one that never existed, with no new code at those call sites.
Only the retention-internal `findPendingCleanup`/`findPendingArtifactPurge` queries still see these
runs, to resume a crash-interrupted pass.

**A real orchestration bug, found only by running the crash-resume tests, not assumed**: the first
`RetentionService.sweep()` unconditionally called `claimForCleanup`/`claimForArtifactPurge` for
every candidate, including ones already tombstoned by a prior (possibly crashed) attempt. Since the
claim's own contract is "did *this* call just win the race" (`UPDATE ... WHERE ... IS NULL`),
re-claiming an already-claimed run always returned `false` - silently skipping every crash-resume
candidate forever, never actually finishing its deletion.
`crashAfterTombstoneIsResumedByTheNextSweep`/`crashAfterFileDeletionBeforeDbDeleteIsResumedByTheNextSweep`
both failed (`runDeletedCount: 0`) against this implementation. Fixed by tracking
`findPendingCleanup`/`findPendingArtifactPurge` results as a separate `alreadyClaimed` set and
skipping the claim call entirely for them, going straight to idempotent file/row deletion - both
tests pass after the fix.

A second, self-caught ordering issue: `runId` path validation (exact UUID shape, matching what
`RunService.submit` generates) originally ran *after* claiming. Reordered so validation always runs
first - a malformed runId is now never tombstoned at all, so it stays visible and simply fails every
sweep attempt (logged, isolated, retried) instead of being hidden behind a permanently-unfinishable
tombstone. Verified by `refusesToBuildAPathFromARunIdThatIsNotTheExactUuidShape`.

Every delete path is built only from the configured root directory plus a re-validated `runId` -
never from any `relative_path` value read out of the `artifacts` table - and recursive deletion
never follows symlinks (`Files.walkFileTree`'s default, `FOLLOW_LINKS` never requested).

### Tests

`RetentionServiceTest` (Testcontainers Postgres, instance-level `@Container` for a fresh database
per test method, plus real `@TempDir` filesystem dirs) covers every scenario the plan required:
exact age/count boundary, deterministic tie-break, no non-terminal run ever marked, crash-after-
tombstone and crash-after-file-deletion resumption, two concurrent sweeps never double-processing
the same run, the artifact-ingestion-vs-purge race, idempotent repeated sweep, and the
malformed-runId path-safety guard - 10/11 passed; the symlink-traversal test gracefully self-skips
(`Assumptions.abort`) on this Windows dev machine, which lacks unprivileged symlink creation - a
genuine environment limitation, not a code gap. **See this section's own "Review round" below for
how the ingest-vs-purge race is actually enforced** - the original `INSERT ... WHERE EXISTS (...)`
guard alone turned out not to be sufficient under real concurrent transactions.

### Live verification against a real running system

Seeded a real 40-day-old terminal run directly via `psql` plus matching real artifact/log/raw-event
files under the real configured directories, against a real `bootRun` (permissive security chain,
`.env.local` moved aside) with real local Postgres. The run and its files were gone before a manual
`preview` call could even observe them as a candidate - traced via the bootRun log to
`RetentionScheduler`'s own `initialDelay=0` startup tick, which fired immediately at boot and
correctly found and deleted exactly the one seeded run:
`RetentionReport[dryRun=false, runCandidateCount=1, runDeletedCount=1, ..., bytesFreed=22]`.
Confirmed via `psql` (0 rows for that `run_id`) and the filesystem (no artifact directory for it
remains among every other test-generated run directory) that both the DB row and its files are
genuinely gone - an even stronger proof than a manual click-through, since it demonstrates the real
scheduled sweep working end to end against a real system with no manual trigger involved.

### Frontend

`RunResponse` gained `artifactsPurged: boolean` (`artifacts_purge_started_at IS NOT NULL` - see this
section's own "Review round" for why this reflects the moment purge *starts*, not only once it
finishes); OpenAPI schema and the generated TS client regenerated against a real running backend
(`npm run api:check:contract`) - diff confirmed to be exactly that one field addition,
`RetentionController` correctly excluded from the public API doc (`@Hidden`, internal ops surface
only). `RunDetailsPage` shows "Artifacts expired due to retention and are no longer available for
download." when the artifact list is empty and `artifactsPurged` is `true`, instead of the section
silently vanishing (unchanged for the never-ingested case).

### Gates

Backend: `spotlessApply spotlessCheck test` and `:runner-service:databaseIntegrationTest`, both
green (the latter 10/11 pass + 1 environment-limited skip, as above). Frontend: `npm run check`
(format, lint, boundaries, typecheck, Vitest + coverage, build) green at 290/290 after updating
three test files' local `RunResponse` fixture builders (`RunsTable.test.tsx`,
`runner-api.test.ts`, `RunLaunchForm.test.tsx`, `RunDetailsPage.test.tsx`) to include the new
required `artifactsPurged` field - a real, if minor, regression the coverage gate itself caught,
not one assumed away.

### Review round (2026-09-07) - 2 P1 + 2 P2, all fixed and reverified, closed the same session

A second, independent review pass, this time explicitly probing concurrency and rate-limiting
rather than the design's own stated decisions - found real gaps the original test suite's own
interleaving (sequential claim-then-act, never genuinely concurrent transactions) could not have
caught.

1. **[P1] A pending tombstone does not mean the run's earlier claimant is still alive or working
   on it** - a second sweep starting while a first is mid-cleanup (already tombstoned the run, not
   yet deleted its files) sees exactly the same `findPendingCleanup()` result a genuine
   crash-recovery resume would produce, cannot tell the two apart, and would process the run
   concurrently with the first sweep's own in-flight deletion. The existing
   `twoConcurrentSweepsNeverDoubleProcessTheSameRun` test raced two real sweeps but never forced
   this exact interleaving, so it could pass by luck rather than by guarantee. Fixed with a plain
   in-process `ReentrantLock` (non-blocking `tryLock`) around the whole real-sweep body in
   `RetentionService#sweep` - correct and sufficient for this project's own documented
   single-instance architecture (`README.md`'s "Known limitations": one `runner-service` process,
   no clustering); a genuinely multi-instance deployment would need a real DB-level owner/lease
   protocol instead. `RetentionReport` gained a `skipped` field so a caller (the scheduler, or the
   admin-triggered REST endpoint) can tell "nothing was eligible" apart from "another sweep was
   already running." New deterministic test
   `aConcurrentSweepWhileAnotherIsMidCleanupIsSkippedNotDoubleProcessed`: a `RunLifecycleStore`
   decorator pauses the first sweep immediately after its claim succeeds (mirroring this codebase's
   existing `BlockingReplayStore` pattern), and the test proves a second concurrent call is skipped
   entirely - never even reaching `findPendingCleanup` - rather than racing the first.
2. **[P1] `INSERT ... WHERE EXISTS (...)` removes the check-then-insert window within one
   statement, but does not serialize against a concurrent purge transaction** - an ingest
   transaction's own snapshot can be taken before a concurrent `claimForArtifactPurge` commits, yet
   its insert can still commit *after* that same run's purge has already deleted its files and
   `artifacts` rows, resurrecting metadata behind files that no longer exist. Fixed by having both
   `JdbcArtifactRepository#ingest` and `#completePurge` take a real per-run row lock (`SELECT ...
   FOR UPDATE` on `runs`) at the very start of their own transaction, before checking the purge flag
   or touching any row - `claimForArtifactPurge`'s own `UPDATE` already takes the same row lock as
   an intrinsic part of executing, so no change was needed there for it to participate correctly.
   Under Postgres's ordinary row-lock semantics this makes the two protocols mutually exclusive per
   run regardless of which reaches the row first. **Proven with genuine forced concurrency, not
   sequential calls**: two new tests each open a second raw JDBC connection, manually hold an
   uncommitted transaction on the contested row (simulating "purge already claimed, not yet
   committed" and "ingest already inserted, not yet committed" respectively), and assert - via a
   `Future.get(300ms)` timing out - that the other side's real call (`ingest`/
   `claimForArtifactPurge`) genuinely blocks until the lock-holding connection commits, then
   completes correctly immediately after
   (`concurrentIngestIsBlockedThenSkippedWhenPurgeAlreadyHoldsThePerRunLock`/
   `concurrentArtifactPurgeClaimIsBlockedThenSucceedsWhenIngestAlreadyHoldsThePerRunLock`).
3. **[P2] A client could be handed a download link for a file already deleted, or about to be** -
   `findForRun`/`isArtifactsPurged` only reflected `artifacts_purged_at` (set by `completePurge`,
   *after* the on-disk directory is already gone), leaving the entire window between
   `claimForArtifactPurge` and `completePurge` showing stale metadata for files that might already
   not exist. Fixed: both now key off `artifacts_purge_started_at IS NOT NULL` instead - "no longer
   available" the instant purge is claimed, matching when the dashboard's "artifacts expired due to
   retention" message should actually appear. The existing race test was updated (it previously
   asserted the pre-existing artifact stayed listed through this window, which was precisely the
   bug) and a raw-row-count helper was added alongside it, since `findForRun` can no longer be used
   to check "does the row still physically exist" once purge is claimed.
4. **[P2] `GET /api/v1/retention/preview`/`POST /api/v1/retention/run` were `ROLE_ADMIN`+CSRF
   protected but entirely outside the D3.3 `AbuseRateLimitFilter` matrix** - a valid or stolen admin
   session could trigger real DB/filesystem sweeps as often as it liked. Fixed: new
   `runner.retention-rate-limit` (default 10/hour), applied as two independent `AbuseRateLimitFilter`
   surfaces (`retention-preview`/`retention-run`, admin-keyed) so the cheap read-only preview and the
   expensive real sweep never share or starve each other's budget. `SecurityAccessMatrixTest` now
   includes `RetentionController` in its full matrix for the first time: anonymous 401, non-admin
   403, admin 200, missing-CSRF-on-POST 403, and both surfaces' own 429 after the 11th call in the
   window.

All fixes reverified together: full backend `spotlessApply spotlessCheck test` and
`:runner-service:databaseIntegrationTest` green (`RetentionServiceTest` now 10/11 + 1
environment-limited skip, up from 7/8 - three new tests, one existing test corrected), full
frontend `npm run check` unaffected (no frontend code changed this round). No live `bootRun`
re-verification was needed this round - every finding here is proven by a real Postgres/real
concurrent-transaction test, which is the more precise tool for exactly these races than a manual
click-through would be.

## Faza D4.3.1 - liveness and readiness health probes

**Date:** 2026-09-08. **Scope:** real Kubernetes-style liveness/readiness probe groups
(`management.endpoint.health.probes.enabled`), so a genuine failure (DB down, disk exhausted,
stuck D2.5 recovery, a `DEGRADED` runner) actually surfaces as unhealthy - as a real Docker
healthcheck and an operator-visible signal - without ever restart-looping the JVM over a condition
a restart can't fix, and without ever blocking the dashboard's own already-proven
`BackendUnavailableE2eTest` behavior. Plan reviewed and revised once before implementation (Caddy
must not gate its own startup on backend readiness; the `db` indicator's own worst-case latency
must be bounded; `show-details`/`show-components` are two separate settings, both `never`; Caddy
must fail closed on every other `/actuator/*` path) - every correction is reflected below.

### Exposed surface

Exactly three health paths are public, at both the Spring Security layer and the Caddy edge:
`/actuator/health` (root aggregate), `/actuator/health/liveness` (`livenessState` only), and
`/actuator/health/readiness` (`readinessState`, `db`, `recovery`, `disk`, `runnerAvailability`).
Every other `/actuator/*` path - `/actuator/prometheus`, `/actuator/env`, `/actuator/configprops`,
a typo of one of the three above - gets a real Caddy-side `404` (`@actuatorOther` matcher,
`deploy/web/Caddyfile`), never the SPA's `index.html`; `/actuator/info` stays permitted at the
Spring Security layer but is not proxied publicly either way, unchanged from D3.3. `show-details`
and `show-components` are both `never`, so an anonymous caller only ever sees a bare
`{"status": "..."}` on any of the three paths - no contributor name, no exception message, no
file-system path. Three new `HealthIndicator` beans (`RecoveryHealthIndicator`,
`DiskHealthIndicator`, `RunnerAvailabilityHealthIndicator`) wrap
`RunRecoveryService`/`DiskUsageService`/`RunService` respectively; `db` is Spring Boot's own
already-auto-configured indicator, added to the readiness group's `include`, not built here - since
every custom indicator also registers as a top-level contributor by Spring Boot's own default
convention, the plain `/actuator/health` aggregate is now a genuinely more honest signal too, not
just JVM-up.

The status split is deliberate: `db` reports `DOWN` on a genuine Postgres outage - the one failure
class here that actually needs infra/operator action - while `recovery`/`disk`/`runnerAvailability`
report `OUT_OF_SERVICE` instead, matching the existing `RunnerRecoveringException` 503 convention -
temporary, self-resolving conditions, never conflated with `db`'s real failure class.
`spring.datasource.hikari.connection-timeout`/`validation-timeout` (2000ms/1000ms) bound the `db`
indicator's own worst-case latency well below the Docker healthcheck's 4-second `curl --max-time`
bound - HikariCP's own default `connectionTimeout` is 30s, far longer.

### Review round - 3 findings, all fixed and reverified, closed the same session

1. **[P1] The anonymous-access test never exercised the real, OAuth2-configured security chain** -
   `HealthEndpointAnonymousAccessTest` boots with no OAuth2 credentials configured, so
   `SecurityConfig`'s *permissive* chain is the one active; it would stay green even if
   `/actuator/health/liveness`/`/readiness` were accidentally dropped from the real
   `oauth2SecurityFilterChain`'s own `permitAll()` list, since the permissive chain permits
   everything regardless. In PORTFOLIO that regression would mean the Docker healthcheck gets a
   real `401` and the container stays permanently `unhealthy`. Fixed: the identical
   anonymous-GET/no-leaked-detail assertions now also run against
   `OAuth2ChainAppliesAbuseRateLimitTest`'s real OAuth2-configured chain
   (`probeSubPathIsReachableAnonymouslyOnTheOAuth2ChainToo`) - the one every production deployment
   actually runs under.
2. **[P2] Readiness's exact-membership proof said nothing about liveness** -
   `HealthEndpointGroupMembershipTest` locked readiness's five-member set but never locked
   liveness's own single-member set, leaving no permanent guard against a future
   `application.yml` edit silently composing `db`/`disk`/`recovery`/`runnerAvailability` into
   liveness - which would turn a self-resolving condition into a genuine restart-loop. **A real
   landmine was found while fixing this, not merely a missing assertion**: the first attempt set
   `management.endpoint.health.group.liveness.show-components=always` as a test property to make
   liveness's own components visible over HTTP the same way readiness's are - this silently rebound
   the built-in "liveness" probe group away from its own single-member (`livenessState`-only)
   definition, reconstituting its membership as *every* registered contributor instead, which then
   invoked the (deliberately unstubbed, for this test) mocked `DiskUsageService` and blew up with an
   uncaught `NullPointerException` propagating all the way to a raw `500`. Confirmed empirically -
   not assumed - that touching *any* per-group property on Spring Boot's auto-configured
   liveness/readiness groups risks this. Fixed properly: `livenessGroupContainsExactlyLivenessState`
   instead autowires `HealthEndpointGroups`/`HealthContributorRegistry` directly and asserts
   `isMember` against every real registered contributor name - never touches a per-group property,
   never invokes a single indicator's `health()`, so it cannot trigger the same landmine again.
3. **[P2] Deployment docs still described the pre-D4.3.1 health surface** -
   `DEPLOYMENT_ARCHITECTURE.md` claimed only `/actuator/health` was public and described root health
   as "liveness only"; this file listed only one unlimited health endpoint. Both updated to describe
   the three exact paths, the fail-closed `404` for every other `/actuator/*` path, the new root
   aggregate semantics, and the `DOWN`/`OUT_OF_SERVICE` split - this section is that update for the
   release-evidence side.

### Live verification against a real running system

Real `bootRun` + real local Postgres (`localPostgresUp`), no automation run active:

| Scenario | Liveness | Readiness | Notes |
|---|---|---|---|
| Everything healthy | `200 UP` | `200 UP` | root `/actuator/health` also `200 UP` |
| Postgres stopped (`docker stop`) | `200 UP` | `503 DOWN` in ~2.1-2.3s | measured against the literal Docker healthcheck command (`curl --fail --silent --show-error --max-time 4 .../readiness`) - real `curl` exit `22` on the real `503`, well inside the 4s bound; proves the Hikari timeout tuning actually takes effect, not just reasoning that it should |
| Postgres restored (`docker start`) | `200 UP` | back to `200 UP` on its own | no `runner-service` restart involved |
| Disk forced below threshold (`RUNNER_DISK_MIN_FREE_BYTES` set absurdly high, non-destructive - no real disk exhaustion needed) | `200 UP` | `503 OUT_OF_SERVICE` | proves the real `DiskUsageService` -> `DiskHealthIndicator` -> readiness-group wiring end to end, not just the mocked unit test |

Recovery-running and runner-`DEGRADED` rows were not independently forced live (both are
transient/hard-to-safely-reproduce states without real side effects) - covered instead by their
unit tests' exact status-mapping (`RecoveryHealthIndicatorTest`, `RunnerAvailabilityHealthIndicatorTest`)
plus `HealthEndpointGroupMembershipTest`'s proof that `recovery`/`runnerAvailability` are genuinely
registered readiness-group members, not merely assumed ones.

A real `docker compose up` pass (image rebuilt with `curl`, transition `starting` -> `healthy`) and
the Caddy fail-closed matrix against a live Compose stack are deferred to D4.3.4's consolidated
acceptance pass - the identical heavier rebuild either way; every Dockerfile/Compose/Caddy change
needed is already in place.

### Gates

`fullBackendGate` (spotless + every module's `test` + `:runner-service:databaseIntegrationTest`)
and `dashboardE2eTest` both green after every change, including the review-round fixes above.

## Faza D4.3.2 - Micrometer/Prometheus metrics

**Date:** 2026-09-08. **Scope:** a real `/actuator/prometheus` scrape endpoint plus a focused set of
domain metrics (run lifecycle, disk rejections, SSE connections, recovery, retention, disk/DB
size) - deliberately no Prometheus/Grafana container yet (real VPS memory cost too early for this
phase). A first plan draft was reviewed and found to have real correctness gaps in exactly the
areas that matter most for metrics that must never lie or crash a real run; every one of that
review's corrections is reflected in what was actually built, not just planned.

**Locked decision (this session):** `/actuator/prometheus` is `permitAll` on the real OAuth2 chain,
the same posture as the three health paths - a real Prometheus scraper cannot perform an
interactive GitHub OAuth2 login, and stronger protection later should be a separate monitoring
network, not a GitHub session. Still unreachable from outside the Compose network regardless
(Caddy's own `@actuatorOther` matcher fail-closes it externally; `runner-service` publishes no port
in the base `docker-compose.yml`).

### Review round - 2 P1 + 3 P2, all fixed before any code was written

1. **[P1] Lifecycle metrics must not be scattered across `RunService` call sites** -
   `RunEventBroker.transitionIfNonTerminal` can lose its own concurrency race and return empty; a
   metric recorded unconditionally at every one of `RunService`'s ~10 terminal-transition call
   sites could double-count a run two callers raced to finish. Separately, `CANCELLED`/`ERROR` can
   be recorded before a run ever reached `RUNNING` (`startedAt` is `null` then, a real case `Run`'s
   own compact constructor permits), so a naive `Duration.between(startedAt, finishedAt)` at an
   arbitrary call site would NPE. Fixed by centralizing every lifecycle metric in
   `RunLifecycleCoordinator`'s own `queue`/`markRunning`/`finishIfLive` - the one real chokepoint
   that already captures `Optional<CommittedRunChange>` before deciding whether to emit an event;
   metrics are recorded from that same `Optional`, never a separately re-derived boolean. Added
   `runner.runs.submitted` (recorded unconditionally in `queue`, since it never contends) alongside
   `started`/`finished`/`duration`, since `finished` can legitimately exceed `started` for a run
   cancelled before ever launching. Proven with genuine concurrency, not sequential calls -
   `RunLifecycleCoordinatorTest#concurrentFinishAttemptsNeverProduceMoreThanOneRunFinished` (16
   racing threads) now also asserts the `finished` counter and `duration` timer each moved exactly
   once, summed across whichever status tag happened to win.
2. **[P1] A metrics-code error must never change a run's outcome** - `RunnerMetrics` is
   deliberately best-effort: every `record*` method's actual Micrometer interaction is wrapped in
   its own try/catch, logged and swallowed, never propagated into lifecycle code. Proven directly
   (`RunnerMetricsTest#aBrokenRegistryNeverThrowsOutOfAnyRecordCall`) against a `MeterRegistry` mock
   whose every method throws.
3. **[P2] Retention metrics were missing the manual sweep and the whole-sweep-failure case** - the
   first draft recorded metrics only in `RetentionScheduler`, so `POST /api/v1/retention/run`'s
   identical real sweep would have been completely invisible, and a whole-sweep exception (before
   any `RetentionReport` could even be built) would have recorded nothing at all. Fixed by
   instrumenting inside `RetentionService#sweep(false)` itself - the one place both callers
   converge - wrapping the real-sweep execution in try/catch: success records
   `runs_deleted`/`bytes_freed`/`item_failures` from the report, a whole-sweep exception increments
   a separate `sweep_failures` counter and rethrows unchanged, and both the dry-run and
   lock-contention-skipped branches return before reaching any of it - proven live against a real
   Postgres (`RetentionServiceTest`, extended): a dry-run preview records nothing, a real sweep
   records `runs_deleted=1`.
4. **[P2] The sampler needed defined startup, failure, and scheduling behavior** - `DiskMetricsSampler`
   runs on its own dedicated single-thread scheduler (never `RetentionScheduler`'s), uses
   `scheduleWithFixedDelay` (never overlapping), takes its first sample immediately
   (`initialDelay = 0` - confirmed live below), samples `runnerDataBytes()`/`databaseBytes()`
   independently so one Postgres hiccup can never block the other refreshing, and its own scheduled
   task catches `Throwable` as a final backstop - `ScheduledExecutorService` silently cancels all
   future executions of a periodic task the moment one throws, which would otherwise make one bad
   sample the last one ever taken. A failed sample leaves the previous cached value in place and
   increments `runner.metrics.sampler.failures{source}`; paired `*_age_seconds` gauges make
   staleness visible rather than letting a stale value look permanently fresh. The live
   `runner.disk.free_bytes` gauge reads `DiskUsageService.snapshot()` fresh on every scrape and
   returns `NaN` (never propagates the exception into a scrape) when the probe itself fails.
5. **[P2] String parameters didn't lock cardinality** - `recordDiskRejection`/`recordSseRejection`
   now take dedicated enums (`DiskRejectionPhase`, `SseRejectionReason`, `SampleSource`), never a
   free-form `String` - a future caller physically cannot pass a `runId`, an IP, or an exception
   message as a tag. `recordRunFinished` validates its status is terminal before recording.

Minor fixes also applied: `runner.executor.active`/`runner.executor.queued` (not `runner.runs.*` -
they reflect the single-worker executor's own state, which includes `STARTING`/cleanup, not only
`RUNNING`-status runs); the Prometheus dependency is `runtimeOnly` (production code only references
the neutral `MeterRegistry` interface); the not-rate-limited test overrides
`runner.public-read-rate-limit` down to 2/min for 4 requests rather than a 121-request burst against
the real default (its own dedicated context - `PrometheusEndpointIsNotRateLimitedTest` - so it never
conflicts with `OAuth2ChainAppliesAbuseRateLimitTest`'s own real-120/min assertion in the same
class); counters are documented as process-lifetime values, reset on every restart (`RunnerMetrics`'s
own class Javadoc) - a real Prometheus server (not deployed yet - D5) is what retains history.

### Live verification against a real running system

Real `bootRun` + real local Postgres, no authenticated run submitted (out of scope for a quick
spot-check - covered instead by `RunLifecycleCoordinatorTest`'s real concurrent-race proof and
`RunnerMetricsTest`'s full unit coverage of every `record*` method). Confirmed via
`curl /actuator/prometheus` immediately at startup:

- `runner_executor_active`/`runner_executor_queued`/`runner_sse_connections_active` all present at
  `0.0` - gauges register correctly with no traffic yet.
- `runner_disk_free_bytes`, `runner_disk_runner_data_bytes`, `runner_disk_database_bytes` all
  present with real, non-zero values (`~181 GB` free, `~648 MB` runner data, `~7.99 MB` database) -
  and their paired `*_age_seconds` gauges were already non-null moments after startup, proving the
  sampler's first sample really does run immediately rather than waiting the configured 60s
  interval.
- `runner_retention_runs_deleted_total`/`runner_retention_bytes_freed_total` present at `0.0` -
  `RetentionScheduler`'s own `initialDelay=0` startup tick (D4.1) already ran a real sweep against
  the empty fresh database and recorded it, proving the retention-metrics wiring fires on the
  scheduled path too, not only the manual-trigger path already proven in `RetentionServiceTest`.
- Re-checked ~20s later: `runner_disk_runner_data_bytes_age_seconds`/
  `runner_disk_database_bytes_age_seconds` had grown to `44.0`, then reset to `10.0` moments after
  the 60s interval elapsed and the next real sample fired - the fixed-delay scheduling and
  age-tracking both behave exactly as designed under a real running system, not just a mocked test.

### Gates

`fullBackendGate` (spotless + every module's `test` + `:runner-service:databaseIntegrationTest`,
including the real-Postgres `RetentionServiceTest` extensions) green. No frontend changes this
phase - metrics are a backend-only, ops-facing surface - so `dashboardE2eTest` was not re-run.

## Faza D4.3.3 - structured logging and correlation

**Date:** 2026-09-08. **Scope:** a validated, echoed `X-Request-ID` per HTTP request; MDC-based
`requestId`/`runId` correlation, with `runId` explicitly propagated across every per-run background
thread boundary (MDC is thread-local and does not cross threads on its own); a real HTTP access-log
line (`method`/route template/`status`/`durationMs`) with correct *exactly-once* semantics across
the synchronous, SSE/async, and exception paths; Elastic Common Schema (ECS) JSON output in the
real (PORTFOLIO) deployment via Spring Boot 4.1.1's own native structured-logging support; and
bounded Docker stdout log growth in Compose.

**Locked decision (this session):** ECS via Spring Boot 4.1.1's native structured logging
(`logging.structured.format.console=ecs` + `logging.structured.ecs.service.*`), scoped only to the
real deployment via the Dockerfile's own `ENV` lines - never a new Spring profile, never a
third-party encoder (`logstash-logback-encoder`) or a custom `StructuredLogFormatter`. ECS
automatically folds in MDC entries and SLF4J fluent-API key-value pairs with no extra wiring.

### Review round - 3 P1 + 4 P2, all fixed before any code was written

1. **[P1] Access-log must correctly handle async SSE** - `chain.doFilter()` returns the instant
   async processing starts (`RunEventStreamController`'s `SseEmitter`), while the real connection
   stays open, often for minutes; logging immediately there would report a near-zero duration for a
   still-live connection. Fixed with a `jakarta.servlet.AsyncListener` registered only when
   `request.isAsyncStarted()`, logging from `onComplete`/`onError`/`onTimeout` - all three handled,
   guarded by a single `AtomicBoolean` compare-and-set shared with the synchronous path, since both
   `onComplete` and `onError` can fire for the same request. The listener's callback may run on a
   different thread than the original request thread, so it re-establishes `MDC.put("requestId",
   ...)` for the duration of that one log call rather than assuming the original filter's MDC
   context is still active there.
2. **[P1] Access-log must not be skipped when the filter chain throws** - the logging call lives in
   `doFilterInternal`'s own `finally` block, not a separate `catch`+rethrow, so the original
   exception always re-propagates unchanged and the line is written exactly once regardless. At that
   point the container's own eventual error handling has not run yet, so the status is best-effort
   (`response.isCommitted() ? response.getStatus() : 500`), marked with an explicit
   `outcome=exception` field so a reader is never misled into thinking it was directly observed.
3. **[P1] Wrapping only `RunService`'s worker task is insufficient** - three distinct per-run
   background threads exist, not one: `RunService`'s single-worker executor task,
   `GradleProcessRunner`'s own output-drainer thread, and `ListenerEventIngestor`'s own dedicated
   executor (plus the synchronous HTTP-thread cancel path, which previously embedded `runId` only in
   text). Fixed with a small reusable helper, `MdcScope.withMdc(key, value, action)`, which restores
   whatever value the key held before (not a blind `remove`) once `action` completes - applied at
   all four boundaries. Paired with commit-aware lifecycle log lines (`Run queued`/`Run
   started`/`Run finished`) emitted only from `RunLifecycleCoordinator`'s already-gated
   `Optional<CommittedRunChange>` (mirroring D4.3.2's own metrics design exactly), so "every run
   produces at least one real, known log event" is a guarantee, never an assumption a test could
   pass against vacuously.
4. **[P2] `durationMs` must use a monotonic clock** - `System.nanoTime()`, never
   `Duration.between(Instant.now(), Instant.now())`, which a system clock/NTP adjustment mid-request
   could otherwise turn negative or nonsensical.
5. **[P2] Successful health-probe requests must not dominate logs** - a real Docker healthcheck
   polls `/actuator/health/liveness`/`readiness` every 10s (D4.3.1); a successful (2xx) response on
   either logs at `DEBUG` instead of `INFO`, while a failing probe response (a real signal) and every
   other route still log at `INFO`.
6. **[P2] A genuine adversarial secret-leakage test, not just a source-grep** -
   `RequestLoggingFilterSecretLeakageTest` boots the real Spring context and sends a real request
   carrying sentinel values in `Authorization`, `Cookie`, an OAuth `code`/`state` query parameter,
   and the request body, then asserts none of them appear in the captured access-log event.
7. **[P2] Precision on Docker ARG/ENV/build-args semantics** - `ARG APP_VERSION=unknown` declared
   after `FROM` and immediately before the `ENV` line referencing it (an `ARG` before `FROM` is only
   visible to `FROM` itself); `docker-compose.yml`'s `runner-service` service gets a matching
   `build.args.APP_VERSION` so a real build can pass a real version; documented explicitly that a
   *runtime* `environment:` override cannot retroactively change a value already baked in at build
   time via `ARG`/`ENV` substitution.

### A real bug the live acceptance pass caught that the unit tests missed

The first implementation's `isSuccessfulHealthProbe` matched the resolved route *template*
(`HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`) against the literal
`/actuator/health/liveness`/`/readiness` strings. `RequestLoggingFilterTest`'s own mock request set
both the request URI and that attribute to the identical literal path, so it passed - but a real
Spring Boot actuator `WebMvcEndpointHandlerMapping` resolves that attribute to the coarse
`/actuator/health/**` wildcard for every health sub-path, which is never in `HEALTH_PROBE_ROUTES`.
Confirmed live: hitting `/actuator/health/liveness` and `/readiness` against a real `bootRun`
produced two `HTTP request completed` lines at `INFO`, not the expected silent `DEBUG` suppression.
Fixed by matching against `request.getRequestURI()` (the literal path) instead of the route
template, and the test rewritten to set the two attributes to genuinely different values -
mirroring the real mismatch - so this exact regression cannot silently reappear. Re-verified live
after the fix: both probes produced no visible line at the default `INFO` root level, while
`/api/v1/capabilities` still logged normally.

### Live verification against a real running system

Real `bootRun` + real local Postgres (`localPostgresUp`), first with no ECS env vars set (plain,
human-readable console output confirmed unaffected), then with
`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` + the three `LOGGING_STRUCTURED_ECS_SERVICE_*` vars set:

- Real ECS JSON on stdout, `service.name`/`service.version`/`service.environment` present on every
  line, `ecs.version` field present.
- A request with `X-Request-ID: acceptance-test-req-id-001` echoed that exact value back in both the
  response header and the corresponding real `HTTP request completed` log line; a request with no
  header got a freshly generated UUID, likewise identical between header and log line.
- The health-probe DEBUG-vs-INFO bug above was found and fixed during this same pass (see previous
  section).
- Adversarial secret-leakage check repeated against real stdout (not just the unit-test capture):
  sent sentinel values in `Authorization`, `Cookie`, an OAuth `code`/`state` query parameter, and the
  request body to the real `/api/v1/auth/oauth2/callback/github` endpoint - none of the five
  sentinels appeared anywhere in the real log output, while the real access-log line for that exact
  request (`status=403`, blocked by CSRF) was confirmed present.
- Submitting a real authenticated run was out of scope for this spot-check (anonymous requests are
  rejected with 403 "authenticated, but do not have permission" - the same admin-gating boundary
  D4.3.2's own live-verification section already deferred for the identical reason) - `runId`
  MDC-propagation across `RunService`'s worker thread, `GradleProcessRunner`'s drainer thread, and
  `ListenerEventIngestor`'s own executor is instead covered by real thread-boundary tests added this
  phase (`RunServiceTest`'s two new tests, `GradleProcessRunnerTest`'s
  `theOutputDrainerThreadCarriesTheRealRunIdInItsOwnMdc`,
  `ListenerEventIngestorTest`'s `theIngestorsOwnThreadCarriesTheRealRunIdInItsOwnMdc`).

**Docker log-rotation acceptance** - a real `docker compose -f deploy/docker-compose.yml --env-file
deploy/.env up --build` brought up all three containers; `docker inspect` on each confirmed the real
applied `HostConfig.LogConfig`:
`{"Type":"json-file","Config":{"compress":"true","max-file":"3","max-size":"10m"}}` on `web`,
`runner-service`, and `postgres` alike - not just trusted from the Compose YAML. Separately rebuilt
`runner-service` with `APP_VERSION=2.5.7-acceptance` passed as a real build arg and confirmed the
real container's stdout carried `"service":{"version":"2.5.7-acceptance",...}` - the ARG/ENV
build-time substitution genuinely bakes a real version, not just a hardcoded default. Stack torn
down afterward (`docker compose down`, no `-v` - volumes preserved).

### Review round - 5 findings on the shipped code, all fixed and reverified

A review of the actual shipped `RequestLoggingFilter`/`MdcScope`/background-job code (not the plan)
found real gaps the live acceptance pass above had not exercised:

1. **[P1] The async cycle can complete between `isAsyncStarted()` and listener registration** (a
   real race, most realistic for a fast terminal SSE replay) - `getAsyncContext()`/`addListener`
   then throw `IllegalStateException`, which previously escaped the filter and would have turned a
   best-effort observability failure into a real request-processing error. Fixed: caught and
   converted into an immediate best-effort log instead. New test:
   `anAsyncContextThatAlreadyCompletedBeforeListenerRegistrationStillLogsOnceAndNeverThrows`.
2. **[P1] A further `startAsync()` restarts the cycle without keeping the listener registered** -
   the container does not carry a previously-registered `AsyncListener` over to a new async cycle
   on the same request; a second `startAsync()` would have finished with zero access-log lines at
   all. Fixed: `onStartAsync` re-registers itself on the new `AsyncContext`. New test:
   `onStartAsyncReRegistersItselfSoASecondAsyncCycleStillLogsExactlyOnce`.
3. **[P2] `onComplete`/`onTimeout`/`onError` all logged the same way** - a timed-out or errored SSE
   connection could read as an ordinary successful completion, often even with a stale `200`. Fixed
   with a bounded `Outcome` enum (`COMPLETED`/`TIMEOUT`/`ERROR`/`EXCEPTION`), always emitted as a
   structured field; a non-`COMPLETED` outcome's status is best-effort
   (`isCommitted() ? getStatus() : 500`), never the real-but-stale value. New tests:
   `anAsyncTimeoutLogsAsTimeoutNeverAsAnOrdinaryCompletion`,
   `anAsyncErrorLogsAsErrorNeverAsAnOrdinaryCompletion`.
4. **[P2] The filter blindly `MDC.remove`d instead of restoring** - a thread that already carried a
   `requestId` from some outer scope would have that value erased, not restored. Fixed with a new
   `MdcScope.Handle`/`MdcScope.open()` (a checked-exception-free `AutoCloseable`), used via
   try-with-resources at both the filter's own outer scope and `logAccessLineOnce`'s independent
   inner scope. New tests: `restoresTheOuterRequestIdInMdcRatherThanBlindlyClearingIt`,
   `theAsyncCallbacksOwnLoggingRestoresWhateverRequestIdThatThreadAlreadyHadToo`.
5. **[P2] `runId` correlation was incomplete for two background jobs** -
   `ArtifactIngestionService`'s periodic reconciliation pass and `RetentionService`'s per-run sweep
   errors only interpolated `runId` into the log message text, never as a real structured/ECS
   field, and the reconciliation pass's own background thread never had `runId` in MDC at all.
   Fixed: both wrapped in `MdcScope.withMdc`, both failure logs converted to
   `log.atWarn()/atError().addKeyValue("runId", runId)`.

**[P3, also addressed]**: `RunService`/`ListenerEventIngestor` each carried a test-only mutable
field (`testObservedRunIdInMdc`) whose sole purpose was letting a test read internal production
state. Removed from both; the same correlation is now proven through real collaborators already
invoked on the thread that matters - `FakeProcessLauncher.start()` and `RecordingRunEventAppender
.append()` (both already test doubles) capture `MDC.get("runId")` at the exact point production
code already calls them. `GradleProcessRunner`'s own `afterDrainerThreadMdcEstablished()` test seam
was deliberately left as-is - a stateless protected-method-override hook mirroring this codebase's
established `RunEventHub` precedent, not a mutable-state leak.

All reverified together: `spotlessCheck`/`fullBackendGate` green, including the 7 new/rewritten
tests in `RequestLoggingFilterTest` (13 total) plus the updated `ListenerEventIngestorTest`/
`RunServiceTest` assertions.

### Gates

`fullBackendGate` and `dashboardE2eTest` both green (no frontend changes this phase).

## Faza D4.3.4 - consolidated acceptance (the Definition of Done for all of D4.3)

**Date:** 2026-09-08. **Scope:** one holistic live-verification pass tying together D4.3.1
(health/readiness), D4.3.2 (metrics), and D4.3.3 (structured logging) against the real production
Compose topology (`web`/Caddy → `runner-service` → `postgres`), closing every item the plan's own
Definition-of-Done checklist named - including two items D4.3.1/D4.3.2's own docs explicitly
deferred here ("Caddy's fail-closed actuator matrix against a live Compose stack" and "a real
`docker compose up` pass... transition `starting` -> `healthy`"), plus one genuine gap found while
auditing prior coverage (see below).

### A genuine gap found while auditing what was already proven, closed with a new test

Auditing D4.3.1-D4.3.3's own prior live-verification sections before repeating any of them found
one real, previously-flagged-but-waived gap: `RunnerAvailabilityHealthIndicator`'s exact status
mapping was proven at the unit level (`RunnerAvailabilityHealthIndicatorTest`, a mocked
`RunService`) and its registered readiness-group membership separately
(`HealthEndpointGroupMembershipTest`), but nothing had ever driven a real `RunService.isDegraded()
== true` state through to a real, live `/actuator/health/readiness` HTTP response - D4.3.1's own
live-verification round judged forcing an actual process-kill failure live "hard to safely
reproduce" and explicitly waived it at the time.

Closed with a new test, `RunnerAvailabilityDegradedReadinessEndToEndTest` (`@SpringBootTest`,
`WebEnvironment.RANDOM_PORT`, mocking `RunService` directly - the same established pattern
`HealthEndpointGroupMembershipTest` already uses for `DiskUsageService`): asserts the
`runnerAvailability` *component's own* status within the readiness response (via
`show-components=always`), never the top-level aggregate - this context has no real reachable
Postgres, so the real (unmocked) `db` contributor genuinely reports `DOWN` and would dominate the
aggregate regardless of `runnerAvailability`'s own status. Proves: `isDegraded()==true` ->
`runnerAvailability` component reports `OUT_OF_SERVICE`, liveness's own aggregate stays `UP`
(never a reason to restart the JVM); `isDegraded()==false` -> `runnerAvailability` reports `UP`.

### Live verification against the real Compose stack

Real `docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.debug.yml --env-file
deploy/.env up --build` (the debug overlay adds loopback-only direct access to `runner-service`,
bypassing Caddy, for the Prometheus check below - never used in a real deployment):

- **`starting` -> `healthy` transition, with a real timestamped transcript**: container started at
  `15:06:44.883`; Docker's own first healthcheck attempt ran at `15:06:50.069` (5.2s in, well
  within the configured 30s `start_period`) and returned `{"status":"UP"}` with exit code `0` ->
  `docker inspect`'s `.State.Health.Status` immediately read `healthy`. Not just "eventually
  healthy" - the real Log array with Start/End/ExitCode/Output confirms the actual mechanics.
- **Caddy's fail-closed actuator matrix, live-curled through the real published port** (not just
  read from the Caddyfile): `/actuator/health`, `/actuator/health/liveness`,
  `/actuator/health/readiness` all `200`; `/actuator/prometheus`, `/actuator/env`,
  `/actuator/configprops`, `/actuator/beans`, and even a bogus `/actuator/health/bogus` sub-path
  all `404` - the `@actuatorOther` fail-closed matcher works exactly as designed, and a typo'd
  health sub-path is refused rather than silently falling through to the SPA's own `index.html`.
- **`requestId` in both the response header and the real ECS log line, through the real stack**: a
  request with `X-Request-ID: d434-consolidated-check-2` against `/api/v1/capabilities` (through
  Caddy) echoed that exact value in the response header, and the identical value appeared in the
  real ECS JSON log line on `runner-service`'s own stdout (`"requestId":"d434-consolidated-check-2"
  ,"method":"GET","route":"/api/v1/capabilities","status":200,"outcome":"completed"`). A parallel
  check against `/actuator/health/readiness` produced no log line at all - correctly quiet, D4.3.3's
  own DEBUG-level health-probe suppression, not a regression.
- **A real Prometheus scrape, through the debug port, never through Caddy**: `runner_disk_*`,
  `runner_executor_*`, `runner_sse_connections_active`, `runner_retention_*` all present with real
  values; confirmed the identical path returns `404` through the public Caddy port, consistent with
  D4.3.2's own locked decision (reachable only inside the Compose network / via the debug overlay,
  never publicly).
- **Postgres stopped -> readiness `503`/`DOWN`, liveness stays `200`/`UP`, through the real
  Caddy-fronted stack** (not just direct `bootRun`, as D4.3.1's own live check used): `docker stop
  deploy-postgres-1` -> readiness `503 {"status":"DOWN"}`, liveness stayed `200 {"status":"UP"}`;
  `docker start deploy-postgres-1` -> readiness self-recovered to `200` within a few seconds with
  no `runner-service` restart at all.
- **Log-rotation config re-confirmed** on all three containers in this same pass:
  `{"Type":"json-file","Config":{"compress":"true","max-file":"3","max-size":"10m"}}`.

Stack torn down afterward (`docker compose down`, no `-v` - volumes preserved).

### Gates

`fullBackendGate` and `dashboardE2eTest` both green.

**D4.3 (D4.3.1-D4.3.4: health/readiness, metrics, structured logging, consolidated acceptance) is
now fully closed.**

## Faza D4.4.1a - performance-baseline: isolated Compose project + deterministic seed

**Date:** 2026-09-08. **Scope:** the foundational piece of D4.4 (performance baseline) - an
isolated Compose project (`-p runner-performance`, a distinct `runner_performance` database) plus a
one-shot `performance-seed` service that deterministically seeds exactly 500 terminal runs
(including CUSTOM-suite examples, one ~400-event run for SSE replay, and artifact examples), never
touching the real deployment's own data. A first-draft plan was reviewed and found to have 5 P1 +
several P2 gaps before any code was written - see `docs/RELEASE_EVIDENCE.md`'s own memory-linked
plan file and [[feedback_security_plan_review_style]] for the full list; this sub-phase implements
the isolation/seeding corrections specifically (#2 and #3 from that review).

**What shipped**: `deploy/docker-compose.performance.yml` (adds the one-shot `performance-seed`
service only - no `postgres` override needed, since the base `docker-compose.yml` already
parameterizes `POSTGRES_DB` from the env file for both `postgres`'s own init and
`runner-service`'s JDBC URL); `deploy/performance.env` (a new, safe-to-commit env file - fake
OAuth credentials, `POSTGRES_DB=runner_performance`); `performance/seed/` (`Dockerfile` pinned to
the same `postgres:17-alpine` tag the real `postgres` service uses, `seed.sh` - a fail-closed
`current_database()` self-check before anything else runs, then `psql -f seed.sql`, then writes
matching real artifact files onto the mounted `runner-data` volume - `seed.sql` - deterministic,
`generate_series`-driven SQL: 480 plain terminal runs across 5 suites/5 statuses, 19 CUSTOM-suite
runs with `run_selected_tests` rows, one `perf-replay-run` with 399 `run_events` rows, 5
`artifacts` rows).

**A real bug the live verification caught**: the seed used `artifacts.schema_version = '1.0'`;
`ArtifactManifestEntry`'s own compact constructor only accepts its real `CURRENT_SCHEMA_VERSION`
(`"1.1"`), so `GET /api/v1/runs/{runId}/artifacts` returned a real `500` (`Unsupported
ArtifactManifestEntry schemaVersion: 1.0`) the instant it tried to read a seeded row back - caught
by actually calling the real endpoint against the real seeded data, not by inspecting the SQL.
Fixed by using `'1.1'`; re-verified live afterward.

**Live verification against a real isolated stack** (`-p runner-performance`, `postgres` +
`runner-service` + `performance-seed` only - `web`/Caddy omitted, not needed for this sub-phase):
- Exact row counts confirmed via direct SQL: 500 total runs, 19 CUSTOM, 57 `run_selected_tests`,
  399 `run_events` for `perf-replay-run` (sequence 1..399, no gaps), 5 `artifacts`, 5 distinct
  statuses.
- `GET /api/v1/runs/perf-replay-run`, `GET /api/v1/runs` (list, includes the CUSTOM run's real
  `selectedTests`), `GET /api/v1/runs/{runId}/artifacts`, and the real artifact **download**
  (`Content-Length: 68`, matching the real fixture PNG's own measured size - see the review round
  below for why it's a genuine PNG, not a same-size placeholder) all work against the real
  application logic, not just structurally-plausible SQL.
- Real SSE replay of `perf-replay-run` (`GET /api/v1/runs/perf-replay-run/events`) produces
  correctly-shaped `id:`/`event:`/`data:` frames the dashboard's own `EventSource` client expects,
  in the right order, starting from `RUN_QUEUED`.
- **Fail-closed self-check verified for real**: ran the same seed image directly with
  `PGDATABASE=postgres` (a deliberately wrong database) - aborted immediately
  (`current_database()=postgres, expected runner_performance`, exit code 1) before touching
  anything; confirmed the `postgres` database gained no `runs` table at all.
- Stack torn down afterward (`down -v` - fully ephemeral, nothing left behind).

### Review round - 3 P1 + 3 P2, all fixed and reverified before this sub-phase was closed

A review of the actual shipped D4.4.1a code (not the plan) found real gaps the live verification
pass above had not exercised:

1. **[P1] The seed was not idempotent/re-runnable** - plain `INSERT`s meant a second run against
   the same stack failed on primary-key/unique constraints, even though the locked requirement was
   that the seed can safely repeat between D4.4.2's own 3-5 measurement passes. Fixed:
   `seed.sql` now opens with `DELETE FROM runs WHERE run_id LIKE 'perf-%'` (never a global
   `TRUNCATE`, which would also erase anything a concurrent/non-seed process wrote) before
   re-inserting - `run_selected_tests`/`run_events`/`artifacts` all cascade from that one `DELETE`
   via their own `ON DELETE CASCADE` constraints. Live-verified: ran the seed twice against the
   same live stack with no `down -v` in between - first run `DELETE 0` (fresh database), second run
   `DELETE 500` then identical re-insert counts, no constraint errors either time.
2. **[P1] The documented Compose command tore the stack down the instant seeding finished** -
   `up --abort-on-container-exit performance-seed` stops `runner-service`/`postgres` the moment the
   one-shot seed exits successfully, which is exactly wrong for D4.4.1b onward (k6 needs to run
   against a stack still alive *after* seeding). Fixed: the overlay's own header comment and
   `performance/README.md` now document four separate steps - `up -d --build postgres
   runner-service` (long-lived services only) → `run --rm --build performance-seed` (repeatable) →
   run scenarios against the still-live stack → `down -v` as an always-run cleanup step.
3. **[P1] The seeded `perf-replay-run` event timeline was not chronologically valid** - `RUN_STARTED`
   was timestamped `base + 5s`, but the first `TEST_STARTED`/`TEST_PASSED` events were timestamped
   `base + 1s`/`base + 2s` - sequence-gapless, but time went *backward* between sequence 2 and 3, a
   journal the real system could never produce. Fixed: every test-level event is now timestamped
   strictly at or after `RUN_STARTED`'s own `occurred_at` (`RUN_STARTED + t seconds` for the t-th
   pair). A runtime acceptance check was added directly inside `seed.sql`'s own transaction (a
   `lag(occurred_at) OVER (ORDER BY sequence)` comparison, raising an exception and aborting the
   whole seed if any regression is found) - not just a one-off manual check, so this cannot silently
   regress again. Live-verified: a direct query for regressions returns `0`, and `RUN_STARTED`
   (sequence 2) at `+5s` is correctly followed by `TEST_STARTED` (sequence 3) at `+6s`.
4. **[P2] Artifact metadata was committed before its file was written** - if `psql` succeeded but a
   file write then failed, a permanent DB row would be left pointing at a nonexistent file.
   Reordered `seed.sh`: (1) the fail-closed database check, (2) writing/validating the real artifact
   files, (3) the one SQL transaction that replaces the seed-owned dataset - if step 3 now fails,
   only a harmless orphan file remains, safely overwritten by the next (idempotent) pass.
5. **[P2] The "PNG" fixture wasn't a real PNG** - 2048 zero bytes with `media_type: image/png` would
   download successfully (byte count matched) but break any real image viewer/thumbnail, silently
   violating the artifact's own claimed content type. Fixed: a genuine, valid 68-byte 1x1
   transparent PNG (`performance/seed/assets/fixture.png`, verified via `file` - "PNG image data, 1 x
   1, 8-bit gray+alpha, non-interlaced") copied onto disk for the five seeded runs; `size_bytes` in
   `seed.sql` now comes from a psql variable populated by `seed.sh`'s own real, measured byte count
   of that file - never a hardcoded constant.
6. **[P2] Isolation depended on an operator remembering `-p runner-performance`** - added
   `name: runner-performance` as `deploy/docker-compose.performance.yml`'s own top-level field, so
   the project name is fixed declaratively regardless of whether `-p` is passed on the command line
   (still recommended for explicit confirmation, now redundant-but-harmless rather than
   load-bearing).

**[P3, also addressed]**: the original "byte-identical between runs" language overclaimed what the
seed actually guarantees - every timestamp derives from the seeding transaction's own `now()`, so
re-running produces the same structure/counts/distribution/relationships but not byte-identical
content. Corrected throughout to "structurally deterministic relative to one transaction's own
`now()`," not byte-identical.

All three P1s and all three P2s reverified together in one real, corrected run (see the live
verification bullets above and the idempotency re-run) - not fixed in isolation and assumed to
still compose correctly.

### Gates

No Java/Gradle source touched this sub-phase (new files live under `performance/`/`deploy/` only,
outside every Gradle source set) - no `fullBackendGate`/`dashboardE2eTest` re-run needed.

**D4.4.1a is now closed.**

## Faza D4.4.1b - performance-baseline: public-read/artifact-reads/health k6 scenarios

**Date:** 2026-09-08. **Scope:** the first real k6 scenarios, run against the real production
topology (k6 -> Caddy -> `runner-service` -> PostgreSQL, never `runner-service` directly), with the
review-locked expected/unexpected `429`/`5xx` classification and per-endpoint tagged sub-metrics.

**What shipped**: `performance/k6/lib/metrics.js` (shared `expected_429`/`unexpected_429`/
`unexpected_5xx` `Counter`s, an `unexpected_error_rate` `Rate`, and a per-endpoint-tagged
`success_latency_ms` `Trend` populated only for a genuine `200` - a `429`'s own fast response time
never contributes to the real success-path latency distribution); `performance/k6/lib/summary.js`
(one shared `handleSummary()` every scenario uses, no remote `jslib.k6.io` import - offline/
reproducible, `JSON.stringify`-only on k6's own `data.metrics`); `performance/k6/public-read.js`
(capabilities/tests/runs-list/run-detail, the real 120/min `public-read` bucket - a `429` here is
an *expected* signal); `performance/k6/artifact-reads.js` (artifacts-list/artifact-download, the
real 30/min `download` bucket - deliberately lighter VU profile); `performance/k6/health.js`
(liveness/readiness - never rate-limited at all, so a `429` there would itself be a bug,
`rateLimitExpected=false`). `deploy/docker-compose.performance.yml` gained a one-shot `k6` service
pinned to `grafana/k6:1.5.0` (verified this exact tag exists and pulls cleanly) on the same `edge`
network `web` is on - the real topology, not a shortcut to `runner-service` directly.

**Live verification against the real isolated stack** (full lifecycle: `postgres`+`runner-service`
up, `performance-seed` run, `web` joined, then each k6 scenario run via `docker compose run --rm
k6`):

- **`public-read.js`** (5 VUs, 30s): 600 requests total - 120 genuine `200`s
  (`success_latency_ms` populated, p95≈60ms) and 480 `expected_429`s (the 120/min bucket
  overwhelmed by design at this VU count) - `unexpected_429`/`unexpected_5xx` absent (zero),
  `unexpected_error_rate` = 0. The `"{endpoint}: 429 carries Retry-After"` check passed all 480
  times (`checks` rate = 1).
- **`artifact-reads.js`** (2 VUs, 30s): 120 requests - 30 genuine `200`s, 90 `expected_429`s (the
  much lower 30/min `download` bucket correctly overwhelmed faster), same clean
  zero-unexpected-anything result, `Retry-After` present on all 90.
- **`health.js`** (2 VUs, 15s): 60 requests, all genuine `200`s, `success_latency_ms` p95≈5.5ms/
  p99≈7.5ms - no `429`s at all (correct - health isn't in `AbuseRateLimitFilter`'s covered surface),
  `unexpected_error_rate` = 0.
- A Git Bash (MSYS) path-mangling artifact (`/scripts/public-read.js` auto-converted to a Windows
  path) was hit and worked around with `MSYS_NO_PATHCONV=1` - not a real Compose/k6 issue, noted
  here only so a future session on the same shell doesn't re-diagnose it from scratch.
- Stack torn down afterward (`down -v` - fully ephemeral, nothing left behind).

### Review round - 3 P1 + 2 P2, all fixed and reverified before this sub-phase was closed

A review of the actual saved k6 JSON results (not the plan) found real gaps live verification
above had not caught:

1. **[P1] Expected `429`s still counted as k6's own `http_req_failed`** - k6's built-in classifier
   treats any non-2xx/3xx as failed by default, so the saved results showed `http_req_failed.rate =
   0.8` (public-read) / `0.75` (artifact-reads) despite the custom `unexpected_error_rate` correctly
   staying `0` - directly contradicting the "an expected 429 is never a failure" design intent, and
   would have corrupted the aggregate status a later published baseline reports. Fixed:
   `http.setResponseCallback(http.expectedStatuses(200, 429))` for the two rate-limited scenarios
   (`http.expectedStatuses(200)` only for `health.js`, where a `429` would itself be a bug) - the
   independent custom classification in `lib/metrics.js` still separately verifies a `429` is
   genuinely well-formed. Live-verified: `http_req_failed.rate` is now `0` in all three scenarios.
2. **[P1] Per-endpoint latency sub-metrics were never actually generated** - tagging a `Trend`
   sample does not by itself make k6 track a separate `success_latency_ms{endpoint:...}` series;
   only referencing that exact tag combination in `options.thresholds` does, and the first draft
   never did. Fixed: each scenario's own `options.thresholds` now references
   `success_latency_ms{endpoint:<name>}` for every endpoint it covers (permissive `p(95)<100000`
   placeholders - real empirical values come in D4.4.2), and `lib/summary.js`'s `handleSummary` gained
   a runtime acceptance check that a named endpoint has a real success count > 0, throwing (loudly
   logged, `hint="script exception"`) otherwise - **live-verified as a genuine, firing check**: a
   deliberately-broken `capabilities` validator produced exactly the expected thrown error
   ("summary has no recorded successful responses for: capabilities") and `unexpected_error_rate`
   correctly rose to 5% (30/600) - then reverted and reconfirmed clean.
3. **[P1] A `200` with invalid content still counted as success** - `recordOutcome` recorded
   `success_latency_ms` for any `200` regardless of body, so a misrouted SPA fallback, an empty/
   wrong runs list, a mismatched run-detail, a non-`UP` health body, missing artifact metadata, or a
   corrupted download would all have stayed falsely green. Fixed: every call site now passes a real
   per-endpoint validator (capabilities/tests shape, `runs-list` has exactly 500 entries,
   `run-detail`'s `runId` matches what was requested, health body has `status:"UP"`, artifacts-list
   contains the expected `artifactId`, artifact-download checks real `Content-Type` + byte length +
   PNG magic bytes via a `responseType:'binary'` request) - `success_latency_ms` is only recorded
   when both the status *and* the validator pass. Live-verified: artifact-download's real PNG
   magic-byte/size/content-type check passed for all 14 real downloads in one run.
4. **[P2] The `Retry-After` check didn't affect classification** - a `429` was counted as
   `expected_429` first, with the `Retry-After` presence check only asserted afterward as an
   independent `check()` - a missing/malformed header would have failed the check while the
   response still counted as expected and `unexpected_error_rate` stayed `0`. Fixed: `Retry-After`
   validity (present, positive, integer) is now part of computing whether a `429` is *actually*
   expected at all - an invalid one routes to `unexpected_429` and raises
   `unexpected_error_rate`.
5. **[P2] `run-detail`/artifact rotation used `__ITER`, a per-VU counter** - all 5 (or 2) VUs
   independently restarted from the same small subset (`perf-run-0001...`), so 30 seconds mostly
   re-hit an already-warm handful of rows instead of spreading lookups across the real 480-row/
   5-artifact dataset - a real risk of unrealistically fast results from DB/filesystem cache
   warming. Fixed: `exec.scenario.iterationInTest` (`k6/execution`, a globally-unique counter across
   every VU in the scenario) replaces `__ITER` for the modulo index in both `public-read.js` and
   `artifact-reads.js`.

**Also fixed (documentation correction, not code)**: the original write-up above compared a k6/ECS
`429` sample against a Micrometer series filtered to `status="200"` and called it "three angles
agree" - a real methodological error (neither the same status nor the same individual request).
Corrected: **Micrometer must be described as a pre/post aggregate delta over the same route/status/
measurement window, never as proof of one specific request** - only the k6-to-ECS pairing can prove
the *same individual request*, and only because of the fix below. Every `getAndClassify` call now
carries a deterministic, `RequestLoggingFilter`-valid `X-Request-ID`
(`perf-<scenario>-i<globalIter>-<endpoint>`, e.g. `perf-pubread-i0-runs-list`), verified via a
`check()` that the response actually echoed the same id back. Live-verified: the exact id
`perf-pubread-i0-runs-list` was found in k6's own request and, byte-for-byte, in the real ECS log
line on `runner-service`'s own stdout for that same request - genuine same-request proof, not
inferred from route+status+rough timing.

All five findings reverified together in three full scenario runs (not fixed in isolation and
assumed to still compose): `http_req_failed = 0` in every scenario, every scenario's `handleSummary`
produced its full expected set of per-endpoint sub-metrics with no thrown acceptance error, the
deliberately-broken-validator test proved the acceptance check and content validation both fire for
real, and the deterministic request-id proved genuine k6-to-ECS correlation.

### Gates

No Java/Gradle source touched this sub-phase - no `fullBackendGate`/`dashboardE2eTest` re-run
needed.

**D4.4.1b is now closed.**

## Faza D4.4.1c - performance-baseline: two SSE fixtures + the pinned custom xk6-sse image

**Date:** 2026-09-08. **Scope:** the SSE scenarios - replay/time-to-first-event against a terminal
run, and the real per-IP concurrent-connection cap against a still-live one - requiring a custom k6
build, since stock k6 has no native SSE support.

**What shipped**: `performance/k6-sse/Dockerfile` - a multi-stage build (`golang:1.25-alpine` ->
`alpine:3.20`) producing a custom k6 binary via `xk6 build v1.5.0 --with
github.com/phymbert/xk6-sse@v0.1.12` - every version pinned exactly: k6 v1.5.0 (locked with
`lib/summary.js`'s own output-format assumptions, same as the stock `k6` service), `xk6` v0.13.4,
and `xk6-sse` v0.1.12 - a real, verified-existing tagged release (`go get
github.com/phymbert/xk6-sse@latest` resolves to this exact version), never a floating branch/
commit, with both base images additionally pinned by their exact content digest (see the review
round below for why a version tag alone isn't enough, and precisely what is/isn't guaranteed as a
result). A custom pre-built image is for making the *test-run* step offline/reproducible, not
because there is no other way to add extensions to k6 - `xk6-sse` is an unaudited-by-Grafana
community extension, pinned precisely for that reason. `deploy/docker-compose.performance.yml`
gained the `k6-sse` service (same volume/network
shape as the stock `k6` service) and a new `performance-seed-live` service sharing
`performance-seed`'s own image via an explicit `command:` override (`./seed-live-run.sh
insert|delete`) rather than a fixed `ENTRYPOINT`. `performance/seed/seed-live-run.sql`/`.sh`:
idempotent insert/delete of `perf-hold-open-run` (a `RUNNING` row), gated on `runner-service:
condition: service_healthy` - structurally guaranteed to run only after the app's own
readiness/recovery pass has completed, since `/actuator/health/readiness` cannot report `UP` until
`RunRecoveryService`'s one-time startup pass finishes. `performance/k6/sse-replay.js` (connection-
establish time, time-to-first-event, full-replay duration against `perf-replay-run`) and
`sse-connection-cap.js` (4 concurrent VUs against `perf-hold-open-run`, proving the real
`SseConnectionsPerIpTracker` cap).

**Real implementation obstacles resolved by actually building and running this, not by assuming**:

- `xk6 build`'s own CLI requires the version positional argument to come *after* every `--with`/
  `--output` flag - the reverse order fails with a confusing "missing flag" error. Not documented
  anywhere obvious; found only by trying both orderings against the real tool.
- k6 v1.5.0 requires Go >= 1.24 (`golang:1.23-alpine` fails outright); `xk6-sse@latest`'s own
  transitive test dependencies (`onsi/gomega`) then required Go >= 1.25 - settled on
  `golang:1.25-alpine` as the build stage.
- **A real timing bug found only by running the connection-cap scenario live**: Go's
  `http.Client.Timeout` bounds the *entire* request including "awaiting headers," and Spring's
  `SseEmitter` does not flush real HTTP headers until the first byte actually goes out (an event,
  or the periodic heartbeat - `runner.sse-heartbeat-interval`, 15s by default). A first attempt used
  an 8s client-side `timeout` (shorter than that heartbeat interval) to bound the otherwise-
  never-closing hold-open fixture - every one of the up-to-3 genuinely-accepted connections then
  timed out waiting for headers that were never going to arrive within 8s, indistinguishable (from
  k6's own error message) from a connection that was silently rejected. Diagnosed by adding a
  temporary `console.log` of the real response object, which showed `status: 0` and `"context
  deadline exceeded (Client.Timeout exceeded while awaiting headers)"` for all three "accepted"
  attempts. Fixed by raising `HOLD_OPEN_SECONDS` to 20s (comfortably past the 15s heartbeat) -
  re-verified live with the correct classification.

**Live verification against the real isolated stack** (full lifecycle including `web`):

- **`sse-replay.js`** (1 VU, 5 iterations against `perf-replay-run`): every iteration returned a
  real `200` and received exactly 399 events (1995 total / 5 = 399, matching the seeded count
  precisely) - the server correctly completes a terminal run's own SSE subscription on its own, no
  client-side timeout needed. `sse_connection_establish_ms` (~12-135ms) and
  `sse_time_to_first_event_ms` (~0-1ms, since replay starts flowing immediately once the connection
  opens) both populated; `checks` rate = 1 (10/10 - both checks, per iteration, all passed).
- **`sse-connection-cap.js`** (4 VUs, 1 iteration each against `perf-hold-open-run`, `20s` hold):
  `sse_accepted_connections = 3`, `sse_rejected_connections = 1`, `sse_unexpected_status` absent
  (zero) - exactly matching `SseConnectionsPerIpTracker`'s real default cap of 3 concurrent
  connections per client IP (every VU in this one k6 container genuinely shares the same real
  client IP on the Docker network - no IP-spoofing needed, this is the real mechanism under real
  load). The rejected connection's own `429` genuinely carries `Retry-After: "5"` (confirmed via the
  same debug capture) - `checks` rate = 1.
- `perf-hold-open-run` confirmed genuinely `RUNNING` (not reclassified to `ERROR` by D2.5's own
  recovery) immediately after `performance-seed-live`'s insert step, and confirmed fully deleted
  (`count = 0`) after the connection-cap scenario's own cleanup step.
- Stack torn down afterward (`down -v` - fully ephemeral, nothing left behind).

### Review round - 3 P1 + 2 P2, all fixed and reverified before this sub-phase was closed

A review of the actual saved k6 output (not the plan) found real gaps in what a "live-verified,
green" result above actually guaranteed:

1. **[P1] A failed replay still returned a successful k6 exit code** - `check()` alone only
   records a pass/fail *result*; without a real threshold referencing it, a regression (a wrong
   HTTP status, an incomplete replay) would still exit `0`. Fixed: a dedicated `sse_replay_correctness`
   `Rate` (true only when the *whole* replay's contract holds - see finding 2) plus a
   `sse_transport_errors` `Counter`, both with real failing thresholds (`rate==1`/`count==0`), and
   `checks: ['rate==1']` as a second, independent gate.
2. **[P1] The replay only checked a minimum event count** - `eventCount >= 399` would have accepted
   duplicates, extra events, wrong ordering, or malformed frames. Fixed: `validateReplay` now
   requires the exact count (`=== 399`, never `>=`), parses every event's own `data` (a JSON parse
   failure is itself a correctness failure, never silently skipped), and checks strictly
   consecutive `sequence` values from 1, every event's `runId`/`schemaVersion`, and that the first
   event is `RUN_QUEUED` and the last is `RUN_FINISHED`.
3. **[P1] The 3-accepted/1-rejected split was never actually asserted** - the `Counter` metrics
   only described what happened; a regression letting all 4 connections through (or rejecting more
   than 1) could still exit `0`, and in some such cases the existing `Retry-After` check would never
   even run. Fixed: real failing thresholds (`sse_accepted_connections: ['count==3']`,
   `sse_rejected_connections: ['count==1']`, `sse_unexpected_status: ['count==0']`), plus a
   dedicated `sse_retry_after_valid` `Rate` (present *and* a positive integer, reusing
   `lib/metrics.js`'s own `isValidRetryAfter` - exported for exactly this reuse) with its own
   `rate==1` threshold. **Live-verified as genuinely failing, not just theoretically**: deliberately
   setting `EXPECTED_ACCEPTED = 4` produced k6 exit code `99` and a real logged threshold-crossed
   error - reverted, re-confirmed exit `0` on the correct code.
4. **[P2] The hold-open fixture represented an impossible `RUNNING` state** - a real `RUNNING` run
   always already has its own `RUN_QUEUED`/`RUN_STARTED` events and `next_event_sequence = 3` by
   the time anything can observe it; the fixture had empty history and `next_event_sequence = 1`.
   This was not just a realism gap - it was the actual root cause of the earlier 8s-timeout bug: a
   real run's SSE subscription replays its buffered events immediately on connect, flushing real
   response bytes right away, while an empty-history fixture sends nothing until the next periodic
   heartbeat (15s). Fixed: `seed-live-run.sql` now seeds those same two canonical events and sets
   `next_event_sequence = 3` - live-verified the fixture now flushes immediately, letting
   `HOLD_OPEN_SECONDS` drop from the workaround value of 20s to a stable 5s (the real fix, not
   the earlier symptom-level one).
5. **[P2] The custom image's reproducibility claim overstated what it actually guaranteed** - Docker
   tags aren't immutable (`golang:1.25-alpine`/`alpine:3.20` can resolve to different content on a
   later pull), and `go install`/`xk6 build` fetch dependencies over the network, so the *build*
   itself is neither offline nor guaranteed byte-identical across rebuilds. Fixed: both base images
   pinned by their exact content digest (`golang@sha256:1ae0735f...`, `alpine@sha256:d9e853e8...`,
   captured 2026-09-08) - the strongest practical guarantee available - with the Dockerfile's own
   comment, this file, and `performance/README.md` all corrected to state precisely what is true:
   pinning (including by digest) makes *running* the already-built image offline/reproducible; the
   *build* step still needs network access and has no byte-for-byte guarantee.

All five reverified together: the corrected `sse-replay.js`/`sse-connection-cap.js` both pass
cleanly against the real stack, the negative test (finding 3) proves the new thresholds genuinely
gate the exit code, and the corrected fixture (finding 4) is what let the connection-cap timeout
shrink to a stable, real value instead of a workaround.

### Gates

No Java/Gradle source touched this sub-phase - no `fullBackendGate`/`dashboardE2eTest` re-run
needed.

**D4.4.1c is now closed.**

**Next**: D4.4.1d (the WireMock OAuth stub + isolated `create-run.js` scenario) - checking in with
the user before starting, per this sub-phase's own review checkpoint.

## Faza D4.4.1d - performance-baseline: WireMock OAuth stub + isolated create-run.js scenario

**Date:** 2026-09-08. **Scope:** the one genuinely admin-gated, real-process-launching scenario -
a real GitHub OAuth2 admin login, one real `202 Accepted` latency measurement, the per-minute
rate-limit proof, and explicit cleanup of the launched run.

**What shipped**: `deploy/docker-compose.performance.yml` gained a `wiremock` service
(`wiremock/wiremock:3.9.1`, `--global-response-templating`, `profiles: ["tools"]`) standing in for
github.com, plus a `runner-service` overlay block redirecting the real Spring OAuth2 client's
provider URIs (`authorization-uri`/`token-uri`/`user-info-uri`/`user-name-attribute`) at it -
mirrors `OAuthFlowE2eTest`'s own established pattern (stub only the external dependency, keep the
app's own real `ClientRegistrationRepository`/OAuth2 binding untouched). `performance/wiremock/
mappings/{authorize,token,user-info}.json` cover the three real server-to-server calls Spring's
OAuth2 login flow makes: the authorize redirect (templated to echo the real request's own
`redirect_uri`/`state` back), the token exchange, and the user-info lookup (`id: 999001`, matching
`deploy/performance.env`'s own `RUNNER_SECURITY_ADMIN_GITHUB_ID`). `performance/k6/create-run.js`:
performs the real 3-hop OAuth2 redirect chain, confirms a real admin session via
`/api/v1/auth/me`'s `canManageRuns`, measures one real `202` latency
(`create_run_success_latency_ms`), consumes the remaining per-minute budget with two deliberately
Bean-Validation-invalid requests (an oversized `testKeys` list - fails `@Size(max=25)` inside
Spring's own request binding, before `RunController.create` ever calls `RunService.submit`, so no
extra real Gradle process launches), confirms a 4th request in the same window is rate-limited
(`429` with a valid `Retry-After`), and explicitly cancels whatever run it actually launched -
never left running into teardown. `performance/README.md` and the overlay's own header comment
updated with the corrected 4-step (+3b/3c) lifecycle.

**Real implementation obstacles resolved by actually building and running this, not by assuming**:

- **WireMock's own healthcheck failed with a genuine `404`** - `wget --spider` (used by every
  other service's healthcheck in this overlay) defaults to a HEAD-equivalent request, and
  WireMock's `/__admin/health` endpoint only implements GET. Confirmed live via `docker exec ...
  wget --spider ...` showing a real `404 Not Found`. Fixed by switching to `curl --fail --silent
  --show-error` (a real GET) - container then reached `healthy` correctly.
- **The real admin session was never established, and the one "valid" request also failed** - the
  3-hop redirect chain all returned the expected `302`s, but `/api/v1/auth/me` never reflected
  `canManageRuns: true`. Diagnosed by manually tracing the flow with `curl`'s own cookie-jar file
  against the published host port: the `Set-Cookie` response carried `Secure; HttpOnly;
  SameSite=Lax`, and the cookie-jar file's own `secure_flag=TRUE` confirmed - empirically, not by
  assumption - that a standards-compliant client (curl, and k6's cookie jar behaves identically)
  never sends a `Secure`-flagged cookie back over a subsequent plain-HTTP connection, regardless of
  same-origin. Since this isolated stack has no real TLS, the session (and the server-side-stored
  `OAuth2AuthorizationRequest`/`state` it carries) was being silently dropped on every hop. A first
  attempted fix (only `SERVER_SERVLET_SESSION_COOKIE_SECURE=false`) was caught and reverted before
  ever running it, upon re-reading `RunnerSecurityEnvironmentPostProcessor`'s own source and
  confirming it fails closed at startup when `RUNNER_DEPLOYMENTPROFILE=PORTFOLIO` (the base file's
  unchanged default) is combined with a non-Secure cookie. Fixed correctly by adding *both*
  `SERVER_SERVLET_SESSION_COOKIE_SECURE=false` and `RUNNER_DEPLOYMENTPROFILE=LOCAL_DEV` -
  reusing the exact escape hatch `deploy/.env.example`'s own `SESSION_COOKIE_SECURE` comment
  already documents for precisely this situation (a local, non-TLS Compose run), never a novel
  weakening. Confirmed this pairing is accepted at startup and does not affect D4.4.1a-c's own
  scenarios, none of which exercise `Environment.LOCAL` or any other profile-specific behavior.
- **`down -v` (without `--profile tools`) silently left `wiremock` running** - the first teardown
  attempt failed with `Network runner-performance_edge Resource is still in use`;
  `docker ps -a --filter label=com.docker.compose.project=runner-performance` showed
  `runner-performance-wiremock-1` still `Up (healthy)`. Root cause: `docker compose down` is just
  as profile-scoped as `up` - without `--profile tools`, Compose never considers a
  `profiles: ["tools"]` service part of the "current" set to tear down at all. Fixed by re-running
  with `--profile tools`, which correctly stopped/removed `wiremock` and the `edge` network; the
  overlay's own header comment and `performance/README.md` both corrected to require it.

**Live verification against the real isolated stack**:

- Full pass: the real 3-hop OAuth2 redirect chain (backend -> WireMock stub -> real callback) all
  succeeded, CSRF correctly re-primed after the session rotated on login, one real `202`
  (`create_run_success_latency_ms` ~= 171ms), two real `400`s (oversized `testKeys`), one real
  `429` with a valid `Retry-After` (confirmed via `isValidRetryAfter`), and a real cleanup `cancel`
  - confirmed via a direct `GET /api/v1/runs` query that the launched `SMOKE` run's final state was
  genuinely `CANCELLED` (`exitCode: 143`), not merely assumed from the `200` cancel response. k6
  exit code `0`; `create_run_correctness: rate=1`; `checks` 11/11.
- **Negative test**: re-running the scenario within the same 60s rate-limit window against the
  same admin id correctly produced k6 exit code `99` (the real per-minute budget genuinely
  exhausted, proving the thresholds actually gate the exit code, not just describe the outcome) -
  and a follow-up `GET /api/v1/runs` confirmed zero runs were orphaned by the failed attempt
  (exactly the one prior `CANCELLED` run, no non-terminal runs at all).
- Stack torn down afterward with `--profile tools down -v` - confirmed clean (no leftover
  containers/networks/volumes for the `runner-performance` project).

### Gates

No Java/Gradle source touched this sub-phase - no `fullBackendGate`/`dashboardE2eTest` re-run
needed.

**D4.4.1d is now closed.**

### Addendum - scoping the OAuth override out of the always-applied overlay (2026-09-08)

A design tension was flagged before proceeding to the plan's step-5 checkpoint (rather than decided
unilaterally): the checkpoint calls for "one production-policy (real, unmodified
`deploy/docker-compose.yml`) measurement-only pass across every scenario," but the `runner-service`
overlay block above was unconditional for the whole performance overlay - D4.4.1a-c's own
scenarios would therefore also have run under `LOCAL_DEV`/non-Secure-cookie instead of the real
`PORTFOLIO` posture they were originally validated against. **User's resolution**: split the
OAuth-only override into its own file, `deploy/docker-compose.performance-auth.yml`, applied only
around `create-run.js` and reverted immediately after - `wiremock` and the
`SERVER_SERVLET_SESSION_COOKIE_SECURE=false`/`RUNNER_DEPLOYMENTPROFILE=LOCAL_DEV`/provider-URI
overrides moved there entirely, out of `docker-compose.performance.yml`. `create-run.js`'s own
results are now documented as an authenticated write-path measurement under an isolated HTTP
(non-TLS) test override, not a fully production-identical TLS/OAuth result - real Secure-cookie/TLS
acceptance for the write path is deferred to D5.

**Live-verified, not assumed - all three required states, against the real stack**:
1. **Base (no override)**: `docker inspect` showed `RUNNER_DEPLOYMENTPROFILE=PORTFOLIO`/
   `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`; a real `curl` against
   `/api/v1/auth/oauth2/authorization/github` (through the published `web` port) showed
   `Set-Cookie: JSESSIONID=...; Path=/; Secure; HttpOnly; SameSite=Lax` and a `Location` pointing at
   the real `https://github.com/login/oauth/authorize`.
2. **With the auth override applied** (`up -d --build runner-service wiremock` with all three
   compose files): Compose logged a real `Recreate`/`Recreated` for `runner-service` (confirming
   the config change is actually picked up, not silently ignored); `docker inspect` showed
   `RUNNER_DEPLOYMENTPROFILE=LOCAL_DEV`/`SERVER_SERVLET_SESSION_COOKIE_SECURE=false`; the same curl
   now showed `Set-Cookie: JSESSIONID=...; Path=/; HttpOnly; SameSite=Lax` (no `Secure`) and a
   `Location` pointing at `http://wiremock:8080/...`. `create-run.js` re-ran cleanly against this
   state: exit `0`, `create_run_correctness: rate=1`; an immediate re-run within the same 60s
   rate-limit window again correctly produced exit `99`, and `GET /api/v1/runs` confirmed zero
   orphaned/non-terminal runs (exactly one prior run, `CANCELLED`) - the split changed nothing
   about the scenario's own already-verified correctness.
3. **Reverted (auth file omitted again)**: Compose again logged a real `Recreate`/`Recreated` (plus
   a `Found orphan containers (wiremock)` warning - expected and correct, since `wiremock` is now
   outside this narrower file set until teardown includes it again); `docker inspect` showed
   `RUNNER_DEPLOYMENTPROFILE=PORTFOLIO`/`SERVER_SERVLET_SESSION_COOKIE_SECURE=true` again; the same
   curl showed `Secure` restored on `JSESSIONID` and the `Location` pointing at real
   `https://github.com` again.

Teardown re-verified with all three compose files + `--profile tools`: every container (including
the "orphaned" `wiremock`), network, and volume for the `runner-performance` project removed
cleanly - confirmed via `docker ps -a`/`docker network ls`/`docker volume ls` all returning empty
for that project afterward.

`performance/README.md` and both compose files' own header comments rewritten to describe the
recreate/run/revert sequence precisely (see `deploy/docker-compose.performance-auth.yml`'s own
header comment for the canonical version).

**Next**: the plan's step 5 review checkpoint (or D4.4.2), per this engagement's established
rhythm of confirming before crossing a sub-phase boundary.

## Review checkpoint - one production-policy pass across every D4.4.1 scenario (2026-09-08)

**Scope**: the plan's own step 5 - "one production-policy (real, unmodified
`deploy/docker-compose.yml`) measurement-only pass across every scenario," run for the first time
as one continuous, unbroken stack lifecycle (not each scenario tested in isolation the way D4.4.1a-d
each were individually) - confirms every scenario genuinely works together end to end against the
real security/recovery machinery before any threshold or CI work begins in D4.4.2.

**Sequence executed exactly as `performance/README.md` documents**: `up -d --build postgres
runner-service` (real, unmodified `PORTFOLIO`/`Secure`-cookie config) -> `performance-seed` (500
terminal runs) -> `up -d --build web` -> `public-read.js` -> `artifact-reads.js` -> `health.js` ->
`performance-seed-live insert` -> `sse-replay.js` -> `sse-connection-cap.js` ->
`performance-seed-live delete` -> the create-run auth-override recreate/run/revert sequence
(`docker-compose.performance-auth.yml` applied only around `create-run.js`, then reverted) ->
teardown (`--profile tools down -v`, all three compose files).

**Results, every scenario green in the same unbroken lifecycle**:
- `public-read.js`: exit `0`; `checks: rate=1` (1200/1200); `unexpected_error_rate: 0`.
- `artifact-reads.js`: exit `0`; `checks: rate=1` (240/240); `expected_429: 90` (the 30/min download
  bucket correctly triggering); `unexpected_error_rate: 0`.
- `health.js`: exit `0`; 0 failed checks.
- `sse-replay.js`: exit `0`; `sse_replay_correctness: rate=1` (5/5); `sse_transport_errors: 0`.
- `sse-connection-cap.js`: exit `0`; `sse_accepted_connections` exactly `3`,
  `sse_rejected_connections` exactly `1`, `sse_unexpected_status: 0`, `sse_retry_after_valid:
  rate=1`.
- `create-run.js` (auth override applied via a real Compose `Recreate`, confirmed live):
  exit `0`; `create_run_correctness: rate=1`; `checks: rate=1` (11/11); a real ~107ms `202`
  latency; the launched `SMOKE` run confirmed genuinely `CANCELLED` via a direct
  `GET /api/v1/runs` query afterward (no orphaned/non-terminal runs) - then a real Compose
  `Recreate` back to the unmodified config, confirmed via `docker inspect`
  (`RUNNER_DEPLOYMENTPROFILE=PORTFOLIO`/`SERVER_SERVLET_SESSION_COOKIE_SECURE=true` restored).
- Teardown (all three compose files + `--profile tools`) confirmed fully clean via
  `docker ps -a`/`docker network ls`/`docker volume ls` all returning empty for the
  `runner-performance` project afterward.

Every scenario ran against the real, unmodified `deploy/docker-compose.yml`'s own `runner-service`
except for the narrow, explicitly-reverted `create-run.js` window - exactly the posture the
checkpoint's own "real, unmodified" framing requires, now genuinely true rather than assumed
(see the D4.4.1d addendum above for why this required the auth-override split in the first place).
No new code changed this checkpoint - a pure verification pass confirming D4.4.1a-d's individually-
verified scenarios also hold together as one lifecycle, seed-to-teardown, in a single run.

**D4.4's review checkpoint (plan step 5) is now closed.**

## Faza D4.4.2a - performance-baseline: the throughput overlay

**Date:** 2026-09-08. **Scope:** the first slice of D4.4.2 - a genuine throughput measurement pass,
distinct from the production-policy pass every prior sub-phase validated. Real rate limits stay on
by default; this slice raises them for `public-read.js`/`artifact-reads.js`/`health.js` only, so the
*backend*, not `AbuseRateLimitFilter`, is what gets measured.

**Design decision, made with the user before writing code**: heavier concurrency needed both a
raised-limit Compose overlay AND more VUs/longer duration on the existing scripts - reusing the
same scripts (env-configurable per-scenario VUs/duration + an explicit `PERFORMANCE_PROFILE` flag
changing 429 classification), not separate `-throughput.js` scripts, so validation/metrics logic
never needs to be kept in sync across duplicates. `sse-connection-cap.js` and `create-run.js` were
deliberately excluded from the throughput profile: the former exists specifically to prove the
real, fixed per-IP SSE cap (a security mechanism that must never vary by profile), and the latter
is never load-tested at all per its own D4.4.1d header comment (real Gradle process launches, not
a read-path endpoint) - raising limits for either would test a fundamentally different thing.

**What shipped**: `performance/k6/lib/profile.js` - `resolveProfile()` (validates
`PERFORMANCE_PROFILE` is exactly `"production-policy"` or `"throughput"`, defaulting to the former),
`resolveVus`/`resolveDuration` (strict validation - a positive integer VU count under a sane
ceiling, a real k6 duration string - failing script load immediately on anything else, per-scenario
env var names like `PUBLIC_READ_VUS` rather than one shared `VUS` so raising load on one scenario
can never leak into SSE/create-run, which don't import this module at all).
`public-read.js`/`artifact-reads.js`/`health.js` updated: VUs/duration now resolved through
`lib/profile.js`, `rateLimitExpected` now `!THROUGHPUT` at every call site (previously hardcoded
`true`), and all three gained real, always-enforced thresholds
(`unexpected_error_rate: rate==0`, `unexpected_429: count==0`, `unexpected_5xx: count==0`) that
were missing before this sub-phase - a real gap against this project's own established "checks
alone don't gate exit code" pattern, closed here rather than only for the new throughput path,
since an unexpected error/429/5xx is equally a bug in the production-policy pass. New
`deploy/docker-compose.performance-throughput.yml` - raises `public-read-rate-limit`/
`download-rate-limit` (via `RUNNER_PUBLICREADRATELIMIT_MAXATTEMPTS`/`_WINDOW` and
`RUNNER_DOWNLOADRATELIMIT_MAXATTEMPTS`/`_WINDOW` - Spring Boot's relaxed environment-variable
binding for a nested `RateLimitRule` record field, confirmed against the same convention already
used by `RUNNER_DEPLOYMENTPROFILE`) to 1,000,000/min - no other `RunnerProperties` limit is
touched. `performance/README.md` gained a full "Throughput profile" section documenting the
mechanism, the exclusion of SSE/create-run, and the stepped-escalation methodology.

**Live-verified against the real isolated stack, not assumed**:
- Applied the throughput overlay via the same recreate/revert pattern as the auth overlay: real
  Compose `Recreate`, confirmed via `docker inspect` (`RUNNER_PUBLICREADRATELIMIT_MAXATTEMPTS=
  1000000` etc. present); a direct 130-request curl loop against `/api/v1/capabilities` returned
  `200` all 130 times (would have hit `429` at request 121 under the old ceiling) - the override
  genuinely took effect, not just present in the container's env.
- Stepped VU escalation for `public-read.js` (10 -> 20 -> 40 -> 80 VUs, 30s each), checking
  HikariCP pool state (`hikaricp_connections_active`/`_pending`, read from inside the container -
  the published `web` port never exposes `/actuator/prometheus`, a deliberate D4.3.2 decision)
  before and after each step: every step exit `0`, `checks: rate=1` throughout (2320/2320 ->
  4800/4800 -> 9600/9600 -> 19200/19200), `unexpected_error_rate`/`unexpected_429`/`unexpected_5xx`
  all `0` at every step, HikariCP pool never left `0` active/`0` pending at any step, per-endpoint
  p95/p99 degraded gracefully (single-digit ms at 10 VUs up to `runs-list` p95≈35.5ms/p99≈76ms at
  80 VUs) rather than spiking - no saturation observed through 80 VUs on this dev machine.
  `artifact-reads.js` also re-verified at 15 VUs (900 requests, 1800/1800 checks, zero unexpected
  errors) - confirms the same mechanism works for its own, separately-raised `download-rate-limit`.
- Reverted `runner-service` back to the real base config afterward: `docker inspect` confirmed no
  `RATELIMIT` override env vars remained; the same 130-request curl loop then correctly showed
  exactly 120 `200`s + 10 `429`s again - the real 120/min ceiling genuinely restored, not merely
  assumed from the compose file no longer being included.
- Full teardown confirmed clean (`docker ps -a`/`network ls`/`volume ls` all empty for the project).

**Explicitly not yet a locked threshold**: 80 VUs showing no saturation on a local dev laptop under
Docker Desktop says nothing about the real deployment target's own capacity - this project's own
plan (P1 correction #5) deliberately defers locking real latency thresholds to actual GitHub-hosted
CI-runner measurements, never a number any single local machine produces. This sub-phase's job was
only to prove the throughput mechanism itself works end to end, which it does.

### Gates

No Java/Gradle source touched this sub-phase - no `fullBackendGate`/`dashboardE2eTest` re-run
needed.

**D4.4.2a (the throughput overlay) is now closed.**

**Next**: D4.4.2b - the measurement-only-CI-first calibration order (P1 correction #5): a
`performance-test.yml` GitHub Actions workflow (`workflow_dispatch`, thresholds reported but never
enforced yet), run 3-5 times on the real GitHub-hosted runner type, before any threshold gets
locked. This step structurally requires the user's own GitHub Actions execution (see
[[feedback_no_github_push_access_this_env]] - no push/workflow-dispatch-trigger access in this
environment) - flagged to the user before drafting the workflow itself.
