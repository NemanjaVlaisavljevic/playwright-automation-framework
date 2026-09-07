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
