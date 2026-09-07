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
| Health check | `/actuator/health` | unlimited | — | Not matched by any rule in the filter, by construction |

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
