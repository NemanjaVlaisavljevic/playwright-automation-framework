# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Java + Playwright UI/API automation framework testing [Restful Booker Platform](https://automationintesting.online/), a public shared demo app. It is not a library — all framework code lives under `src/test/java` because the repository's product is the executable test suite itself.

## Commands

```bash
./gradlew.bat playwrightInstall   # first-time setup; installs the configured browser (chromium by default)
./gradlew.bat test                # default suite: everything except @Tag("mutation") and @Tag("fixture")
./gradlew.bat smokeTest           # critical-path subset (@Tag("smoke"))
./gradlew.bat apiTest             # read-only API tests only
./gradlew.bat uiTest              # read-only UI tests only
./gradlew.bat journeyTest         # read-only cross-layer (API+UI) tests only
./gradlew.bat regressionTest      # full read-only regression suite
./gradlew.bat mutationTest        # opt-in: writes to the shared public sandbox — refuses to run against it
                                   # unless allowMutationAgainstSharedTarget/ALLOW_MUTATION_AGAINST_SHARED_TARGET=true
./gradlew.bat fixtureTest         # opt-in: controlled fixtures for exercising runner drill-down/reconciliation —
                                   # StepDrilldownFixtureTest always fails on purpose; CancelDuringStepFixtureTest
                                   # deliberately blocks mid-step so a real E2E test can cancel it deterministically;
                                   # never in CI
./gradlew.bat spotlessApply       # auto-format (Google Java Format) — run before finishing any change
./gradlew.bat spotlessCheck       # formatting gate, no changes written
./gradlew.bat allureReport        # static HTML report from existing build/allure-results

./gradlew.bat localSutUp          # local Docker SUT: fetch pinned upstream source, build, start all 7 containers
./gradlew.bat localSutHealth      # wait for every service's health endpoint (no fixed blind startup delay)
./gradlew.bat localSutVerifyRunning # fast-fail health check only, no dependsOn on up/build/prepare — precondition for localJourneyTest
./gradlew.bat localTest           # run the full regression + mutation suite against the local stack (http://localhost)
./gradlew.bat localJourneyTest    # journey suite incl. mutation against the local stack — assumes the stack is already up
./gradlew.bat stabilityTest       # rerun localTest N times (-PstabilityRuns=N, default 10) to check determinism
./gradlew.bat localSutDiagnostics # dump `docker compose ps`/`logs` to build/diagnostics/rbp/; no dependsOn on up/build, safe any time
./gradlew.bat localSutDown        # stop the local stack
./gradlew.bat localSutReset       # stop the local stack and remove its containers/volumes
```

`localTest` is the mutation-suite target intended for real use — it points `baseUrl` at the local stack, so the shared-target guard never blocks it. See `infra/rbp/README.md` for the full local-SUT task list; it also documents a small local patch (`infra/rbp/patches/`) needed to work around a build-time env var bug in upstream's own `assets` Dockerfile. `localTest` passes 27/27 and `stabilityTest -PstabilityRuns=10` has passed 10/10.

The runner-service dashboard's `Environment` dropdown offers `LOCAL` (mapped to `Suite.JOURNEY` only, via `RunCatalog`) alongside `PUBLIC` — it launches `localJourneyTest`, which includes `mutation`-tagged journey tests safely (writes only to `http://localhost`, never the shared public target). The runner **never** starts/stops/manages the local stack itself: a developer must run `localSutUp` by hand first, or a `LOCAL` run will reach a real process and fail fast at its `localSutVerifyRunning` precondition instead of running any tests. See `infra/rbp/README.md`'s "Dashboard `LOCAL` runs" section for the full step-by-step flow.

## CI

Two GitHub Actions workflows, both pinned to full commit SHAs (with a version comment):
- `.github/workflows/quality-gate.yml` — `spotlessCheck` + the default read-only `test` suite, on every push to `main` and every PR.
- `.github/workflows/local-sut.yml` — `localTest` (full read-only + mutation regression) against the local Docker Compose stack, on a weekly schedule plus manual `workflow_dispatch`. Not wired to push/PR: fetching upstream source and building/starting 7 containers is too slow for that. On failure it runs `localSutDiagnostics` before uploading evidence, and always tears the stack down (`localSutDown`, `continue-on-error: true`) regardless of outcome.

Both workflows run their `Test` task with `--rerun`: `setup-gradle` caches the Gradle User Home between runs, and this project has `org.gradle.caching=true` plus a local build cache (`settings.gradle`), so a `Test` task can otherwise come back `FROM-CACHE` instead of actually re-executing against the live app. `.m2`/Docker-layer caching is a separate matter and is **not** wired up — the `rbp-m2-cache` Docker volume and any Maven/Gradle dependency cache never persist across CI's ephemeral runners, so every `local-sut.yml` run still does a cold Java-service build; deliberately deferred rather than blocking this workflow's rollout.

Run a single test class/method with standard Gradle test filtering, e.g.:
`./gradlew.bat test --tests "dev.vlaisanem.automation.tests.api.RoomApiContractTest"`

Cross-browser / non-headless run:
```bash
./gradlew.bat playwrightInstall -Pbrowser=firefox
./gradlew.bat uiTest -Dbrowser=firefox -Dheadless=false
```

Configuration is via system properties (take precedence) or environment variables — see the table in README.md (`baseUrl`/`BASE_URL`, `browser`/`BROWSER`, `headless`/`HEADLESS`, `actionTimeoutMs`, `navigationTimeoutMs`, `tracing`, `recordVideo`, `artifactsDir`, `adminUsername`, `adminPassword`). Checked-in credential defaults are the app owner's public demo credentials, not secrets.

## Non-negotiable test taxonomy

`AutomationExtension` (`core/AutomationExtension.java`) enforces this at runtime and **fails the test with `ExtensionConfigurationException` if violated** — this is not a style guideline, it's a hard gate:

- Exactly one layer tag: `api`, `ui`, or `journey` (a cross-layer test is `journey` only, never also `api`/`ui`)
- Exactly one feature tag: `auth`, `room`, `booking`, or `message`
- Exactly one effect tag: `read-only` or `mutation`
- The `regression` tag, always
- `smoke` additionally if it belongs in the critical-path suite

Any test that can mutate shared state — including a negative write expected to be rejected — must be tagged `mutation` and use `@ResourceLock("restful-booker-platform-mutations")` (mutation tests run serialized against each other because the public target's data resets periodically). `mutation` tests are excluded from the default `test` task and CI; they must be run explicitly.

`AutomationExtension` additionally refuses any `mutation`-tagged test at runtime when `baseUrl` equals the shared/public target (`TestConfig#sharedTargetBaseUrl()`, same default as `baseUrl` itself), unless `allowMutationAgainstSharedTarget`/`ALLOW_MUTATION_AGAINST_SHARED_TARGET=true` is explicitly set. Point `baseUrl` at the local Docker SUT (`localTest`) instead of opting into this for routine work.

`fixture` is a separate, additional marker tag (like `smoke`) for controlled, on-demand fixtures that back specific runner/dashboard verifications rather than testing the app under test: `StepDrilldownFixtureTest` deliberately-and-always fails its third step (step/failure/artifact drill-down, Faza B) and `CancelDuringStepFixtureTest` deliberately blocks mid-step (cancellation/`INTERRUPTED`-reconciliation drill-down, Faza C4.1) so `CancelE2eTest` can cancel it deterministically instead of racing an ordinary suite's timing. Each still carries a real layer/feature/effect/`regression` tag set like any other test, but both are excluded from every real Gradle task (`test`, `smokeTest`, `apiTest`, `uiTest`, `journeyTest`, `regressionTest`, `localTest`) the same way `mutation` is, and only `fixtureTest` (or the dashboard's `FIXTURE` suite, which maps to it) includes them — together, in the same run.

## Architecture

```
core     → JUnit extension + lifecycle only, no app-specific selectors/endpoints
config   → TestConfig: validated, typed system-property/env config (immutable record, loaded once)
api      → HTTP clients (AuthClient, RoomClient, BookingClient, MessageClient) + ApiResult/ApiContextFactory
model    → immutable Java records for wire/domain contracts
data     → test-data factories and AutoCloseable "Managed*" resources (ManagedRoom, ManagedBooking) for cleanup
support  → JSON (de)serialization, JSON Schema validation, secret redaction
ui       → page/component objects (pages/, components/) — no generic BasePage; each exposes domain behavior
tests    → api/, ui/, journey/ — test intent and assertions live here, not in page/API objects
```

Flow: a test requests only the JUnit-injected parameters it needs (`Page`, `BrowserContext`, `APIRequestContext`, `ApiContextFactory`, `TestConfig`) → `AutomationExtension` lazily builds a per-test `TestFixture` → fixtures pull from a per-execution `RuntimeRegistry` that owns one Playwright/Browser per parallel worker (reused across tests; browser *contexts* and API request contexts are always test-scoped/fresh). API-only tests never launch a browser.

Key conventions to preserve when adding code:
- **No generic `BasePage`.** Page/component objects assert their own load contract and expose domain methods; test-level business assertions stay in the test class.
- **`ApiResult`** (`api/ApiResult.java`) is the return type of every API client call — it does not throw on non-2xx; callers assert `status()`/`isSuccessful()`/`bodyAs(...)`/`schemaErrors(...)` explicitly. Never make a client silently swallow a failed response.
- **Authenticated API calls** use a separate `APIRequestContext` obtained via `ApiContextFactory.withCookie(...)`, keeping anonymous and authenticated sessions isolated (mirrors Playwright storage-state isolation for UI contexts).
- **Cleanup**: resources created by a mutation test (`ManagedRoom`, `ManagedBooking`) are `AutoCloseable` and cleaned up in a `try (...)` block; cleanup failures surface as suppressed exceptions rather than masking the original test failure.
- **Locators**: prefer role/label locators; use `data-testid` only where no stable user-facing contract exists (see `ContactForm` for a documented example — a real accessibility bug in the app under test, not a framework workaround to hide).
- Never add `Thread.sleep`, implicit waits, or assertion-free tests. Playwright web-first assertions replace polling.
- Framework code (`core`, `config`, `api`, `support`) must stay app-agnostic — no Restful Booker Platform–specific knowledge outside `model`, `ui`, `data`, and `tests`.
- A workaround for a known app bug must link the issue/README note and make the accepted (buggy) behavior an explicit assertion, not a suppressed check.

## Before finishing a change

Run `./gradlew.bat spotlessApply test` (formats + runs the full read-only suite). Only run `mutationTest` when intentionally writing to the shared sandbox.

Further reading: `docs/ARCHITECTURE.md` (lifecycle/isolation rationale), `docs/TEST_STRATEGY.md` (risk-based coverage rationale), `CONTRIBUTING.md`.
