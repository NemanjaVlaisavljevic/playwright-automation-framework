# Playwright Java Automation Framework

[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![Playwright](https://img.shields.io/badge/Playwright-1.62-2EAD33?logo=playwright)](https://playwright.dev/java/)
[![JUnit](https://img.shields.io/badge/JUnit-6.0-25A162?logo=junit5)](https://junit.org/)

A portfolio-grade UI and API automation framework built with Java, Playwright, JUnit, Gradle, AssertJ, JSON Schema, and Allure.

The system under test is [Restful Booker Platform](https://automationintesting.online/), a public Bed & Breakfast application intentionally built for automation practice. It offers a React UI, Spring Boot APIs, authentication, room and booking workflows, and a shared sandbox that resets periodically. Its [source code is public](https://github.com/mwinteringham/restful-booker-platform), so the tests can be based on documented behavior instead of probing an unrelated production website.

## What this project demonstrates

- One Playwright stack for browser and HTTP testing.
- JUnit 6 extension-based lifecycle with parameter injection.
- Browser reuse per parallel worker and an isolated browser context per test.
- Web-first assertions and accessibility-oriented locators; no implicit waits or sleeps.
- Typed API clients and Java records separated from test intent.
- Anonymous and cookie-authenticated API sessions isolated through Playwright storage state.
- JSON Schema and semantic contract assertions.
- Secret redaction from API evidence and logs.
- Screenshot and Playwright trace capture on UI failure.
- Allure metadata/results plus standard JUnit XML and HTML reports.
- Read-only default CI and explicitly opt-in tests that mutate the shared sandbox.
- Reproducible Gradle wrapper, formatting gate, dependency updates, and artifact upload.

## Quick start

Prerequisites: JDK 21. The Gradle wrapper supplies Gradle itself.

```powershell
# Windows PowerShell
./gradlew.bat playwrightInstall
./gradlew.bat test
```

```bash
# macOS / Linux
./gradlew playwrightInstall
./gradlew test
```

Useful suites:

```bash
./gradlew smokeTest       # UI + API critical path
./gradlew regressionTest  # complete read-only regression suite
./gradlew apiTest         # read-only API checks
./gradlew uiTest          # read-only UI checks
./gradlew journeyTest     # read-only cross-layer (API + UI) checks
./gradlew mutationTest    # opt-in write and negative-write checks
./gradlew spotlessCheck   # formatting quality gate
./gradlew allureReport    # static report from existing results
```

The default `test` task excludes the `mutation` tag. This is deliberate: a professional test suite should not silently alter a shared public environment. Every executable test has exactly one effect tag (`read-only` or `mutation`), one layer tag (`api`, `ui`, or `journey`), one feature tag, and `regression`; the JUnit extension fails fast when that taxonomy is violated. As a second line of defense, the extension also refuses to run any `mutation`-tagged test against the configured shared target (see `sharedTargetBaseUrl` below) unless `allowMutationAgainstSharedTarget` is explicitly set.

## Local Docker target

`mutationTest` and the shared-target guard above exist because the default target is a public, shared sandbox. For unrestricted write/mutation testing, `infra/rbp/` runs the same [Restful Booker Platform](https://github.com/mwinteringham/restful-booker-platform) application locally via Docker Compose, pinned to a fixed upstream commit:

```bash
./gradlew.bat localSutUp       # fetch pinned source, build the 6 Java services + assets, start all 7 containers
./gradlew.bat localSutHealth   # wait for every service's health endpoint (no fixed blind startup delay)
./gradlew.bat localTest        # run the full regression suite, including mutation, against localhost
./gradlew.bat localSutDown     # stop the stack
./gradlew.bat localSutReset    # stop the stack and remove its containers/volumes
./gradlew.bat stabilityTest    # rerun localTest N times (-PstabilityRuns=N, default 10) to check determinism
```

Requires Docker Desktop (with Compose) and `git`; no other toolchain needs to be installed on the host — the Java build runs inside a throwaway Maven container. `localSutPrepare` applies a small local patch on top of the pinned upstream source to fix a build-time env var ordering bug in the `assets` service's own Dockerfile (details, including why it's a patch and not a fork, in `infra/rbp/README.md`). With that patch, `localTest` passes 27/27, and `stabilityTest -PstabilityRuns=10` has passed 10/10.

## Configuration

System properties take precedence over environment variables.

| Purpose | System property | Environment variable | Default |
|---|---|---|---|
| Target URL | `baseUrl` | `BASE_URL` | `https://automationintesting.online` |
| Browser | `browser` | `BROWSER` | `chromium` |
| Headless | `headless` | `HEADLESS` | `true` |
| Action timeout | `actionTimeoutMs` | `ACTION_TIMEOUT_MS` | `10000` |
| Navigation/API timeout | `navigationTimeoutMs` | `NAVIGATION_TIMEOUT_MS` | `30000` |
| Tracing | `tracing` | `TRACING` | `true` |
| Video | `recordVideo` | `RECORD_VIDEO` | `false` |
| Artifact directory | `artifactsDir` | `ARTIFACTS_DIR` | `build/artifacts` |
| Admin username | `adminUsername` | `ADMIN_USERNAME` | `admin` |
| Admin password | `adminPassword` | `ADMIN_PASSWORD` | `password` |
| Shared target guarded against mutation | `sharedTargetBaseUrl` | `SHARED_TARGET_BASE_URL` | `https://automationintesting.online` |
| Opt in to mutating the shared target | `allowMutationAgainstSharedTarget` | `ALLOW_MUTATION_AGAINST_SHARED_TARGET` | `false` |

The checked-in defaults are the demo credentials published by the application owners. For any non-demo environment, pass credentials through CI secrets.

Example cross-browser run:

```bash
./gradlew playwrightInstall -Pbrowser=firefox
./gradlew uiTest -Dbrowser=firefox -Dheadless=false
```

## Project structure

```text
src/test/java/dev/vlaisanem/automation
├── api/          HTTP clients, evidence capture, response abstraction
├── config/       validated environment/system-property configuration
├── core/         JUnit extension and Playwright lifecycle
├── data/         isolated test-data factories
├── model/        immutable API contracts
├── support/      JSON, redaction, and schema validation
├── tests/        API, UI, journey, and opt-in mutation checks
└── ui/           page and component objects
```

Framework code lives in the test source set because this repository is an executable test product, not a library shipped to production. There is intentionally no generic `BasePage`: page/component objects expose domain behavior and keep assertions close to the contract they understand.

See [Architecture](docs/ARCHITECTURE.md), [Test Strategy](docs/TEST_STRATEGY.md), and [Contributing](CONTRIBUTING.md) for the engineering rationale and roadmap.

## Current executable coverage

| Layer | Check | Default run |
|---|---|---|
| API | Admin authentication returns a usable token | Yes |
| API contract | Room inventory matches JSON Schema and domain rules | Yes |
| API | Anonymous guests cannot read or list bookings | Yes |
| API | Anonymous guests can read and list every contact message (documented access-control gap) | Yes |
| API | Anonymous guests cannot update/delete a test-owned booking; invalid bookings are rejected | No, `mutation` opt-in |
| API | Invalid date ranges and overlaps are rejected; adjacent stays are accepted; the known past-date gap is characterized | No, `mutation` opt-in |
| API | Contact message validation rejects blank, malformed, and out-of-range fields | No, `mutation` opt-in on the shared target |
| UI | Guest can load the home page and discover bookable rooms | Yes |
| UI | Admin login succeeds with valid credentials and reports an error with invalid ones | Yes |
| Journey | The homepage renders the first three API rooms as booking actions, in order | Yes |
| API CRUD | Admin creates, reads, updates, and deletes an isolated room | No, `mutation` opt-in |
| API CRUD | Admin creates, reads, updates, and deletes an isolated booking | No, `mutation` opt-in |
| API | Guest submits a valid contact message | No, `mutation` opt-in (no delete endpoint exists) |
| UI journey | Guest completes a booking end to end and it is cleaned up afterward | No, `mutation` opt-in |
| UI | Guest submits the contact form and the API accepts it | No, `mutation` opt-in (no delete endpoint exists) |

The explicit layer, feature, suite, and effect tags allow the future runner UI to discover and safely filter tests without depending on Allure metadata or interpreting the absence of a tag.

This is a strong foundation, not a claim of exhaustive coverage. The next valuable additions are accessibility scanning, OpenAPI-driven contract checks, and timezone-focused booking cases.

### Known application issues found by this suite

The contact form's "Message" `<label>` targets a `for="message"` id that the rendered `<textarea>` does not have (its real id is `description`), so the field has no accessible name. `ContactForm` works around this with a `data-testid` locator (`getByTestId("ContactDescription")`), per the locator policy in [Contributing](CONTRIBUTING.md). This is a real accessibility gap in the application, not a framework bug.

The booking API currently accepts stays dated entirely in the past. The opt-in date-rules suite records this as a `known-defect` characterization test, while invalid ranges, zero-night stays, overlaps, and adjacent boundaries assert their observed contracts normally.

The message API has no authorization check on any read endpoint: `GET /api/message` and `GET /api/message/{id}` return full data, including a guest's email and phone number, to a fully anonymous caller. `MessageAuthorizationApiTest` records this as a `known-defect` characterization test, mirroring how `BookingAuthorizationApiTest` documents the (correct) equivalent behavior for bookings.

### Build tooling backlog

Any build (e.g. `./gradlew help --warning-mode all`) prints three deprecation warnings scheduled for removal in Gradle 10: Kotlin DSL delegated-property syntax (`by creating`/`by registering`) and a `Project` object used as a dependency notation. This repo's own build files are plain Groovy DSL (`build.gradle`, `settings.gradle`) and use none of these patterns — the warnings originate inside an applied plugin's internals (`com.diffplug.spotless:8.10.0` and/or `io.qameta.allure:4.1.0`), not in code we own, so there is nothing to fix here directly. Re-run `--warning-mode all` after the next spotless/allure plugin version bump to check whether upstream has cleared them; if they persist once we're actually on Gradle 10, that's when this becomes a real blocker rather than a backlog note.
