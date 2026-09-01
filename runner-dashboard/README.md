# Runner Dashboard

[![Node](https://img.shields.io/badge/Node-24_LTS-339933?logo=node.js)](https://nodejs.org/)
[![Vite](https://img.shields.io/badge/Vite-8-646CFF?logo=vite)](https://vite.dev/)
[![React](https://img.shields.io/badge/React-19-61DAFB?logo=react)](https://react.dev/)
[![TypeScript](https://img.shields.io/badge/TypeScript-strict-3178C6?logo=typescript)](https://www.typescriptlang.org/)

React/TypeScript frontend for `runner-service` (see `../runner-service`), the Spring Boot backend
that launches this repository's Gradle test suites on demand and streams their progress over SSE.
This dashboard is where a user picks an allowed environment/suite combination, starts a run, and
watches its tests execute live.

It lives in this same Git repository as a sibling of the Gradle modules but is **not** a Gradle
module itself (not listed in `settings.gradle`) - it has its own `package.json` and toolchain.

## Status

`/runs` and `/runs/:runId` are both functional end-to-end against the real backend: pick an
environment/suite from the allowlist `runner-service` actually accepts, start a run, land on its
details page, and watch it progress live over SSE (status, live test-by-test results, cancel while
non-terminal, download the log once it exists) through to a terminal outcome, at which point the
page falls back to the authoritative REST `RunResponse` for the final state. Also done: the app
shell (React Query provider, router, `AppShell` chrome - see below), a generated typed REST client
with a hand-written wrapper (`src/api/runner-api.ts`, `problem-detail.ts`), a hand-written,
thoroughly-tested Zod contract + reducer for the backend's SSE event stream
(`src/domain/runner-event.ts`, `src/features/event-stream/run-event-reducer.ts`), and a small
design-tokens-based UI component kit (`src/components/ui/`) the dashboard is now built on instead
of bare HTML elements.

See the project roadmap for the full phase plan for what's next (packaging, real-browser E2E, etc.).

## Prerequisites

- Node **24** (LTS). `.nvmrc` / `.node-version` pin this; `engine-strict=true` in `.npmrc` makes
  `npm install` refuse an older Node instead of silently proceeding.
- The backend, `runner-service`, running separately (see below) - `/runs` calls it for health and
  capabilities as soon as the page loads.

## Commands

```bash
npm install
npm run dev            # Vite dev server, http://127.0.0.1:5173
npm run build           # tsc -b (project references) + production build
npm run typecheck        # tsc -b, no emit - the same check build performs, run standalone
npm run lint              # oxlint, warnings fail the gate (--deny-warnings)
npm run format:check       # Prettier, check only
npm run format               # Prettier, writes changes
npm run test                  # Vitest, single run
npm run test:watch             # Vitest, watch mode
npm run test:coverage           # Vitest with @vitest/coverage-v8
npm run check:boundaries          # fails if anything outside src/api/ imports src/api/generated/ directly
npm run check                      # format:check && lint && check:boundaries && typecheck && test:coverage && build
npm run api:export                # fetch runner-service's live /v3/api-docs into openapi/runner-api.json
npm run api:generate                # regenerate src/api/generated/ from openapi/runner-api.json
npm run api:check:snapshot           # api:generate, then fail if that alone produced a diff
npm run api:check:contract            # api:export + api:generate, then fail if either produced a diff
```

## Running the backend

`runner-service` is a separate Spring Boot process (Gradle module in the repository root):

```bash
# from the repository root, not runner-dashboard/
./gradlew.bat :runner-service:bootRun
```

This starts the API at `http://127.0.0.1:8080` (`/api/v1/...`, `/actuator/health`, `/v3/api-docs`).
`npm run dev`'s Vite server proxies `/api` and `/actuator` to this port (`vite.config.ts`), so the
frontend only ever calls relative URLs - no CORS configuration to maintain, and the same shape as
the same-origin production deployment this app is ultimately built for (see the roadmap's packaging
phase). Run both processes side by side for `npm run dev` to actually show live data.

## Typed REST client

`src/api/generated/` is produced by [`typed-openapi`](https://github.com/astahmer/typed-openapi)
(`typed-openapi.config.ts`) from `openapi/runner-api.json` - a real export of the backend's
`/v3/api-docs`, committed so contract changes show up in code review, not just in generator output.
**Never hand-edit anything under `src/api/generated/`** - re-export the spec and run
`npm run api:generate` instead.

There are two different drift checks, deliberately not the same script:

- **`npm run api:check:snapshot`** regenerates from the _committed_ `openapi/runner-api.json` and
  fails if that alone produced a diff. Fast, needs no running backend - catches "someone hand-edited
  generated output" or "the committed spec and the committed generated code disagree." It does
  **not** know whether the committed spec itself still matches the real backend.
- **`npm run api:check:contract`** additionally re-exports the spec from a **running**
  `runner-service` first (`api:export`), so it also catches "the backend's actual contract changed
  and nobody regenerated for it" - the gap `api:check:snapshot` alone cannot see. This is the one
  CI (once the frontend pipeline exists, see roadmap) runs after starting the backend.

Both checks compare with `git status --porcelain`, not `git diff --exit-code` - a plain `git diff`
only compares already-tracked files, so it stays silent about a brand-new untracked file (which is
exactly what every file under `src/api/generated/` was before its first commit).

To refresh the spec by hand after a backend change:

```bash
# from the repository root
./gradlew.bat :runner-service:bootRun
# separately, from runner-dashboard/, with the backend still running:
npm run api:export
npm run api:generate
```

`src/api/generated/` and `openapi/runner-api.json` are excluded from `npm run format`/`lint` (the
generated client is machine-formatted by the generator, not by us; the spec should stay exactly
what the server exported) and `src/api/generated/` is excluded from the coverage thresholds in
`vite.config.ts` (it's exercised through contract/integration calls against the real backend, not
unit-tested line by line).

## REST layer

**Invariant: only `src/api/` (the API infrastructure layer) imports from `src/api/generated/`** -
`runner-api.ts` (the wrapper) and `problem-detail.ts` (the `ProblemDetail` schema, for the error
type below). Not "only `runner-api.ts`" - components and everything outside `src/api/` are what
must never import generated code directly, types included: `runner-api.ts` re-exports the
app-facing DTO types (`CapabilitiesResponse`, `CreateRunRequest`, `RunResponse`) precisely so
`domain/`, `features/`, etc. never have a reason to reach into `./generated/` just for a type.
`domain/run.ts` and `RunsTable.tsx` both did exactly that before a review caught it - a documented
convention with nothing enforcing it just drifts, and oxlint's import plugin has no equivalent of
eslint-plugin-import's `no-restricted-paths` to lean on - so `npm run check:boundaries`
(`scripts/check-import-boundaries.mjs`, wired into `check`) fails the build if it happens again.
Components call `runner-api.ts`'s plain functions (`getHealth`, `getCapabilities`, `listRuns`,
`getRun`, `createRun`, `cancelRun` - the process log is just `run.processLogUrl` from a
`RunResponse`, not a separate call) through TanStack Query (`src/api/query-keys.ts` for the query
key shapes); none of them ever call `fetch()` or touch a `Response`/`TypedStatusError`/`ZodError`
directly.

Every failure surfaces as one `RunnerApiError` (`src/api/problem-detail.ts`), discriminated by
`kind` - deliberately three, not collapsed into one:

- **`"http"`** - the backend actually responded with a 4xx/5xx. `problem` (a parsed
  `ProblemDetail`) is populated only when that response body itself was one - an intermediary's own
  error page (e.g. a `502` from Vite's dev proxy when the backend is down) is still `"http"`, just
  with no `problem`.
- **`"network"`** - no HTTP response was ever received at all (`fetch` itself rejected).
- **`"contract"`** - a response _was_ received and parsed as JSON, but its shape didn't match this
  app's own expectations: a generated schema's Zod validation failed, or (for `/actuator/health`,
  hand-validated against a small local schema since it isn't part of the app's own OpenAPI
  document) the body didn't match at all, parsed to `{}`, or wasn't JSON. This is never folded into
  `"network"` - a contract violation means the backend is reachable and responding, which calls for
  a different reaction (and a different alert, eventually) than the backend being down.

`status` is `0` for both `"network"` and `"contract"` (neither has a real HTTP status describing
the actual problem), and the original failure is always attached via the standard `Error.cause`.

**The generated client's own output validation deliberately skips every known 4xx/5xx status**
(see `shouldValidateOutput` in `generated/runner-api.ts` - it only validates success responses and
genuinely unexpected status codes). `runner-api.ts`'s `unwrap` is therefore where `ProblemDetail`
actually gets parsed: it catches the thrown `TypedStatusError`, runs
`ProblemDetail.safeParse(error.response.data)` itself, and only then builds a `RunnerApiError`.
Any future addition to this file that calls the generated client directly must go through `unwrap`
(or something that does the same normalization) - skipping it silently reopens this exact gap.

**Recovery**: neither query retries (`retry: false`, set globally in `query-client.ts`), so
`RunListPage` polls on its own instead: health always, on a modest interval (it's a liveness
check); capabilities only while it's in its error state (its data doesn't change mid-session, so
there's nothing to gain polling it once loaded). Both intervals are accepted as optional props
purely for test speed - the same pattern `App`'s optional `router` prop uses for isolation.

Both queries also set `refetchIntervalInBackground: true`. Verified live, not just in jsdom: with
that flag left at its default (`false`), a real browser tab that is open but not the focused/visible
one (`document.visibilityState === "hidden"`) never polls at all - TanStack Query pauses
`refetchInterval` entirely in that state. That's precisely the scenario this polling exists for (a
dashboard left open while the backend restarts), so relying on the default would have silently
reintroduced the exact gap it's meant to close; jsdom's tests alone didn't catch this; Vitest's
default `document.visibilityState` is `"visible"`, unlike a real backgrounded tab.

Also worth knowing: the generated client's `request()` does `new URL(baseUrl + path)`, and the
WHATWG `URL` constructor rejects a relative string with no base - `createApiClient(fetcher, "")`
throws on every call. `window.location.origin` is used as the base instead, which keeps calls
effectively relative (same-origin, proxied by Vite in dev) without hitting that constructor error.

## SSE event contract and reducer

`src/domain/runner-event.ts` is a **hand-written** Zod discriminated union (on `type`) for the
`RunnerEvent` wire contract - the one contract in this app with no generated counterpart, since
`runner-service` deliberately excludes its SSE endpoint from the OpenAPI document it generates
`src/api/generated/` from (see `docs/SSE_CONTRACT_V1.md` in the repository root). It must be kept
in sync with `runner-contract`'s `RunnerEvent` record by hand. Every field the wire format's
`NON_NULL` serialization can omit is `.optional()`, never nullable - the backend omits an
inapplicable key entirely, it never sends `null` for one. Every variant is `.strict()`: a plain
`z.object()` silently strips an unrecognized key instead of rejecting it, which would have hidden
exactly the cross-scope fields `RunnerEvent`'s own Java constructor actively forbids (a
`RUN_STARTED` carrying `runOutcome`, a run-level event carrying `testId`, a non-`RUN_FINISHED` event
carrying an outcome). `schemaVersion`/`runId`/`testId`/`testDisplayName` reject blank
(whitespace-only, not just empty) strings, matching the backend's own `String.isBlank()` checks -
plain `z.string()` would have accepted both.

`src/features/event-stream/run-event-reducer.ts` applies one raw SSE `data:` payload at a time to a
`RunEventStreamState`, replay or live alike (deliberately the same code path for both - the wire
contract itself makes no distinction). It tracks `eventsBySequence`, a derived `testsById` view, and
a `status` that starts `"active"` and can move to `"gap"` (sequence got ahead of what was expected),
`"protocol-error"` (wrong `runId`, the payload doesn't parse as JSON/as a `RunnerEvent`, or the same
sequence number arrives twice with conflicting content), `"compatibility-error"` (a `schemaVersion`
this build wasn't written against), or `"terminal"` (`RUN_FINISHED`). **Once `status.kind` isn't
`"active"`, the reducer freezes** - every further message is returned unchanged - matching
SSE_CONTRACT_V1.md's own guidance that a client observing a gap should reconnect from scratch, not
try to patch around it.

Validation is staged, deliberately not "parse the full V1 shape, then check the version": a loose
`RunnerEventEnvelope` (just `schemaVersion`/`runId`, no `type` union) is checked _first_, so a real
future V2 event - a brand-new `type` this build has never heard of - is correctly recognized as a
version problem before the strict V1 `RunnerEvent` union ever gets a chance to reject its
unrecognized `type` as a generic "malformed" protocol error. Getting this order backwards is exactly
the kind of thing that looks correct in a test written against a V1-shaped "future version," and
silently misclassifies every _actual_ V2 event once one exists.

A duplicate sequence (`event.sequence <= lastSequence`) is only treated as a benign replay overlap
when its content is identical to what's already recorded at that sequence - the canonical journal's
own gapless-sequence guarantee means two different events can never legitimately share a sequence
number, so a conflicting one is surfaced as a `"protocol-error"` (and freezes the stream) instead of
being silently smoothed over as if it were just a reconnect artifact.

This is deliberately built and thoroughly tested (`run-event-reducer.test.ts`: live sequencing,
replay/live parity, dedup, gap, wrong-runId, malformed JSON, unsupported schema version, terminal +
post-terminal freeze) **before** anything wires it to a real `EventSource` - the reducer is pure and
needs no transport to exercise every one of those scenarios.

`src/features/event-stream/event-stream-client.ts` defines the transport as an
`EventStreamClient` interface (`connect(runId, handlers): { close() }`) with one production
implementation, `EventSourceStreamClient`, wrapping a real `EventSource` against
`GET /api/v1/runs/{runId}/events`. It registers one named listener per `EventType.options` (the
backend uses named SSE events, so the generic `onmessage` handler never fires) and forwards
`open`/`error` verbatim - resuming after a drop is entirely native `EventSource` behavior
(`Last-Event-ID`), not something this class manages. `FakeEventStreamClient`
(`fake-event-stream-client.ts`) is the test double, injected wherever a real `EventSource` would
otherwise be needed: **jsdom has no `EventSource` global at all**, so any test exercising real SSE
behavior must use the fake, not the production client.

`src/features/event-stream/use-run-event-stream.ts` is the hook that owns one run's connection and
feeds every frame - replay or live, identically - into `applyRunnerEventMessage` via `useReducer`,
not a `useState` functional updater. That distinction matters: a state updater/reducer function must
be pure, since React (in development, under `StrictMode`, which `main.tsx` enables) is explicitly
allowed to invoke it twice to check for exactly that. An earlier version of this hook put side
effects - closing the connection, invalidating REST caches - directly inside the `setState` updater;
under `StrictMode` this genuinely double-invoked `queryClient.invalidateQueries` on every
`RUN_FINISHED` (caught by a dedicated `StrictMode`-wrapped regression test, which failed against the
old code before the fix). All such side effects now live in a separate `useEffect` driven off the
resulting `streamState`, which React only re-runs on a real state transition (the reducer returns
the _same_ object reference for a benign duplicate/replay, so no spurious re-fires) - and
`ConnectionState`'s `"CLOSED"`/`"PROTOCOL_ERROR"` values are _derived_ from `streamState.status` at
render time rather than stored via another synchronous `setState` in that effect (also originally
flagged, this time by oxlint's `set-state-in-effect`).

Tracks a `ConnectionState` (`CONNECTING | LIVE | RECONNECTING | RECOVERING | PROTOCOL_ERROR |
CLOSED`). `CONNECTING` (never yet open) and `RECONNECTING` (was open, then dropped) are deliberately
distinct even though native `EventSource` fires the identical `error` event for both
first-connect-failure and a post-open drop - the hook is the one place that remembers `hasBeenOpen`
itself. On **any** non-`"active"` reducer status - not just `RUN_FINISHED`, but also the reducer's
own frozen gap/protocol-error/compatibility-error states - the connection is closed and both
`["runs"]` and `["runs", runId]` are invalidated, so the authoritative `RunResponse` is always
re-read over REST afterward; an earlier version only did this on `RUN_FINISHED`, leaving a page that
had observed a gap stuck showing whatever REST status it last had, possibly forever.

**A `"gap"` gets one bounded fresh-replay attempt before that permanent freeze** - `"gap"`
specifically, never `"protocol-error"`/`"compatibility-error"`, since a gap can be a transient
hiccup while the other two are deterministic contract violations a reconnect cannot fix (malformed
JSON, the wrong `runId`, a conflicting duplicate, or a schema version this build doesn't understand -
retrying any of those would just reproduce the identical failure). On the first gap: the current
connection is closed, the reducer is reset to a brand-new initial state (sequence 0), and a
genuinely new `EventStreamClient.connect()` call is made - reported as `"RECOVERING"` ("Live stream
fell out of sync. Replaying from the beginning…"). This relies on the backend's own SSE contract: a
brand-new `EventSource` carries no `Last-Event-ID` (that's tracked per-`EventSource`-instance by the
browser, not globally per URL), so the server replays the run's full journal from the beginning,
which is exactly how the reducer ends up reconstructing the same test state. If that fresh
connection also gaps, the one-attempt budget (`MAX_GAP_RETRIES = 1`, tracked _per run_, not reset by
a successful recovery) is exhausted and it freezes into `"PROTOCOL_ERROR"` for good, with a link back
to `/runs`. The budget resetting only happens where `streamState`/`connectionState` themselves do -
on an actual remount via `key={runId}` for a different run - never on a successful in-place recovery,
so a run that gaps twice is treated as a real problem rather than retried forever.

Both `"RECOVERING"` (the retry decision) and the counter tracking whether the one-time budget is
still available are implemented as small dedicated `useReducer`s, not `useState`/a ref: the value
needs to be read during render (to avoid ever flashing `"PROTOCOL_ERROR"` for a gap that's actually
about to be retried), which rules out a ref (oxlint's `react(refs)` rule flags reading `.current`
during render), and the retry decision has to run its state update synchronously inside the same
effect that closes/reopens the connection, which rules out a `useState` setter there (the
`set-state-in-effect` problem this file's SSE-transport work has hit more than once already) -
`useReducer`'s `dispatch` is exempt from both concerns.

An earlier version explicitly reset the retry counter (`dispatchGapRetryCount("reset")`) inside the
transport effect - a review caught that this effect's dependency array also includes `client`, so
swapping the injected client for the _same_ `runId` (not a remount) silently refunded an
already-consumed budget, letting one run get more than one fresh-replay attempt. Removed: the
counter's own `useReducer` initial value already starts at `0` for a genuine fresh mount, which is
all `key={runId}` ever produces anyway.

A related review finding: the test proving "a successful fresh replay reconstructs the same test
state" originally only asserted `lastSequence`, the top-level `status`, and one test's partial
status - a bug that dropped a `testDisplayName`, `timestamp`, `detail`, or an entry from
`eventsBySequence` during the reset+replay path would still have passed. It now builds the expected
state by applying the identical, correctly-sequenced events to a clean `applyRunnerEventMessage`
call chain and compares the _entire_ `streamState` (both maps included) against that.

The REST `RunResponse` backing the page header/Cancel gating is otherwise fetched once on mount and
never refreshed again until termination - if that initial `GET` happened to catch the run still
`QUEUED`/`STARTING`, the header (and the Download-log gate, which depends on `startedAt`) would
stay stuck showing that for the entire live run even while the test table below was clearly
updating from SSE. The reducer exposes `runStartedAt` (set once, from `RUN_STARTED`'s own
`timestamp`) specifically so the hook can invalidate `["runs", runId]` a second time, exactly once,
the moment the SSE lifecycle itself confirms the run has moved on.

The hook deliberately does **not** reset its state itself when `runId` changes (that would be
calling `setState`/dispatching synchronously at the top of an effect on every change - the
"resetting state when a prop changes" antipattern); instead, `RunDetailsPage` forces a remount via
`key={runId}` on the component that calls this hook, so a direct navigation from one run's page to
another's (no intervening unmount) still starts with fresh state.

## Dashboard `/runs`

`RunLaunchForm` (`src/features/run-launch/`) drives its suite/environment options entirely off
`GET /api/v1/capabilities` - never hardcoded - so a new environment or suite shows up automatically
the moment the backend's own allowlist (`RunRequestValidator`) grows one. Submitting: locks the
button (`launch.isPending`, no double submit), invalidates `["runs"]` and navigates to
`/runs/{runId}` on success, and turns a failure into one of three messages via `RunnerApiError.kind`

- a `503` ("busy, try again shortly"), a `400` (the backend's own `problem.detail`), or anything
  else (a generic message) - never a raw exception.

`RunsTable` (`src/features/run-list/`) polls `GET /api/v1/runs` - **one shared list poll, not an
SSE connection or a query per row** - and stops on its own once every run in the list has reached a
terminal status (mirrors `RunStatus.isTerminal()` on the backend, see `src/domain/run.ts`). It also
keeps polling (rather than stopping forever) while the query is in its `"error"` state: `data` is
still `undefined` there, and `data?.some(...)` alone evaluates to `undefined` (falsy) - a real gap
found by an error-then-recovery test, since health/capabilities already had their own explicit
error-state check and this one didn't yet. A running row's Duration column keeps advancing because
the poll itself drives a re-render, not a separate timer (`runDurationMs` takes `now` as a parameter
specifically so this stays deterministic in tests - see `src/domain/duration.ts`).

Each row (`RunTableRow`) owns its **own** `useMutation` for Cancel - not one shared instance keyed
by `variables === run.runId`. A single shared mutation only remembers its _last_ `mutate()` call: a
concurrent-cancellation test found that cancelling row B while row A's cancel was still in flight
flipped the shared `variables` to B, silently re-enabling A's button (and losing A's own
pending/error state) while A's request hadn't even resolved yet. Download log is offered once
`run.startedAt` is present, using `run.processLogUrl` from the response directly (not a
frontend-recomputed URL) - **not** "any status past `QUEUED`": the backend can sit in `STARTING`
while waiting out a `DEGRADED` runner, before `ProcessLauncher.start()` has ever run, so the log
file doesn't exist yet even though the status has moved past `QUEUED`. `startedAt` is the one field
`Run`'s own constructor forbids outside `RUNNING`-or-later, which is exactly why it's safe to gate
on instead.

Verified live against a real `runner-service` and a real Gradle-launched `SMOKE` run, not just
against MSW: capabilities-driven form → `202` → redirect → row appears `RUNNING` with an advancing
duration → reaches `SUCCEEDED` → Cancel disappears, Download log stays → polling stops (confirmed
via captured network traffic - 2 requests total across the next 14s, not the ~7 a still-active 2s
poll would have produced).

## Dashboard `/runs/:runId`

`RunDetailsPage` (`src/features/run-details/`) combines the run's own `GET /api/v1/runs/{runId}`
(header: status, suite, environment, requested/finished timestamps, Cancel while non-terminal,
Download log once `startedAt` exists - same gating rules as the `/runs` table) with
`useRunEventStream` for everything below it: a connection banner and a live-updating test table
(`testsById`, sorted by `firstSequence` for stable ordering) with Total/Running/Passed/Failed/
Skipped/Aborted counts. It accepts an optional `eventStreamClient` prop purely for test injection
(`RunDetailsPage.test.tsx` uses `FakeEventStreamClient`; production code never passes it) - the same
pattern as `RunsTable`'s optional poll-interval props.

Cancel's own `useMutation` renders `cancel.isError` as a `role="alert"` with the normalized
`RunnerApiError` message (a 503, a network failure, etc.) - a review caught that an earlier version
created the mutation but never rendered its error state at all, so a failed cancel just silently
re-enabled the button with no explanation of what went wrong.

The connection banner's `"PROTOCOL_ERROR"` text is status-aware, not one generic string for all
three of the reducer's frozen error kinds (see `describeProtocolError` and the fresh-replay recovery
above) - a gap that couldn't recover even after retrying reads differently to a user than an
unsupported schema version - and a `"Back to runs"` link appears alongside it, since there is
nothing left for the page itself to do once the stream has permanently frozen.

Verified live against a real `runner-service` and a real Gradle-launched `SMOKE` run: launching from
`/runs` lands on `/runs/{runId}` already `LIVE`, the test table fills in one row per `TEST_STARTED`
and updates in place on `TEST_PASSED`, and `RUN_FINISHED` closes the connection ("Run finished."),
the Cancel button disappears, and the header's Status/Finished fields update from the REST refetch
the hook itself triggers - all 5 tests in that run reached `PASSED`.

## Design system & UI

Styling is CSS Modules (`*.module.css`, Vite supports this natively - no plugin, no runtime cost)
plus a small set of global, non-module files (`src/styles/`) - deliberately not Tailwind or
CSS-in-JS, to keep the dependency footprint at zero for what a mid-sized dashboard's styling needs
actually are:

- **`tokens.css`** - every raw color/spacing/typography/radius/shadow value lives here as a CSS
  custom property, referenced by name everywhere else. Nothing outside this file should ever
  hardcode a hex value or a raw `px`/`rem` number for something the token scale already covers.
- **`reset.css`** - a small, deliberately incomplete reset, plus one exception to the "no
  `!important`" rule below: forcing near-zero animation/transition durations under
  `prefers-reduced-motion: reduce` is the WCAG-recommended pattern precisely because it must
  out-rank any individual component's own duration, and a per-component opt-in would require every
  future animated component to remember to add it.
- **`global.css`** - `body`/link/`:focus-visible` defaults and the one cross-cutting
  `.visually-hidden` utility class (for a table's accessible caption, etc.) - the one place a
  non-module class name is deliberate rather than a leftover.

Rules followed throughout every component's own `.module.css`: no `!important`; no selector reaching
into another component's internal DOM structure; every visual variant has an explicit name
(`primary`/`secondary`/`danger`, `compact`, `neutral`/`info`/`success`/`danger`/`warning`) rather than
being inferred from usage context; status is never color alone - `StatusBadge`/`Alert` always carry
a text label or icon a color-blind user or screen reader gets too; inline `style` is reserved for
values that are genuinely dynamic per render (`ProgressBar`'s fill `width` is the one real example -
see its own doc comment). Tests assert role/label/behavior, never a CSS class name.

**`src/components/ui/`** is the small reusable kit every feature is built on instead of bare HTML:
`Button` (variant/size), `StatusBadge` (one status vocabulary covering both `RunResponse` and
`TestExecution` statuses - the text is always the visible label), `Alert` (`role="alert"`, an
`aria-hidden` icon reinforcing the tone), `EmptyState`, `LoadingSkeleton` (itself `aria-hidden` - the
actual loading announcement is the page's own status text), `PageHeader`, `MetricCard`, and
`ProgressBar` (`role="progressbar"` with the numeric value also rendered as visible text underneath,
clamped to 0-100).

**`AppShell`** (`src/app/AppShell.tsx`) is a React Router _layout route_ wrapping every page (see
`router.tsx`) - header with the runner-service health indicator (moved here from `RunListPage`,
which used to own it alone: `/runs/:runId` never showed it before), and a sidebar nav (`NavLink`,
so the current page gets `aria-current="page"` for free). Below `640px` the sidebar becomes a
horizontal bar under the header instead of a JS-driven collapsible drawer - there is currently only
the one nav destination to show, so a full hamburger-menu affordance would be complexity without a
matching payoff. The header's own height is a `min-height`, not a fixed `height` (with `flex-wrap`
and vertical padding) - a review caught that a fixed `56px` clipped the full "Runner service
unavailable: ..." message above/below the header at a `320px` viewport with the backend down;
verified live by measuring a real `320px`-wide iframe's layout (`scrollHeight` vs `clientHeight`)
before and after the fix, since jsdom has no layout engine to catch this class of bug in a unit test.

**`RunsTable`** gained client-side search (by `runId`, case-insensitive substring), status/suite
filters (options derived from whatever distinct values are actually present in the loaded list, not
a second query), and click-to-sort column headers (`aria-sort`, native `<button>`s - no custom
keyboard handling needed). All three default to a no-op (empty search, "All"/"All", no active sort),
so the existing tests asserting the table's default row order never needed to change. One subtlety
this surfaced: `getByText("RUNNING")` (or any other status string) became ambiguous once the Status
filter's own `<option>` renders that exact same text - fixed by scoping those lookups to
`getByRole("cell", { name: ... })`, which only matches table cells.

The options list is re-derived from the _current_ poll every render, but `statusFilter`/
`suiteFilter` state is sticky - a review caught that selecting `"RUNNING"` and then having that run
finish (so no run is `"RUNNING"` any more) left the `<select>` showing `"All"` (a native `<select>`
can't display a value with no matching `<option>`) while the table kept filtering against the stale
`"RUNNING"` value underneath, showing "No runs match" for a run that had, in fact, just finished. A
first fix derived an `effectiveStatusFilter` that fell back to `"ALL"` once the stored value dropped
out of the current options - a second review round caught that this only _masked_ the display:
`statusFilter` itself was never actually cleared, so the moment any run matching it reappeared later
(a new "RUNNING" run), the filter would silently "re-arm" itself and start hiding rows again with no
user action at all. Fixed properly instead: the currently-selected value is always kept in the
`<option>` list even once it disappears from the live data, so `statusFilter`/`suiteFilter` are used
directly, completely unmodified, for both the `<select>`'s own `value` and the actual filter
predicate - the selection is never masked, so it can never silently change either. Reproduced and
reverified with two regression tests: the filter staying honestly selected (and matching nothing)
once its run finishes, and a three-poll scenario (`RUNNING` → only `SUCCEEDED` → `SUCCEEDED` plus a
_new_ `RUNNING` run) proving the later match is the filter behaving consistently, not reactivating.

**`RunDetailsPage`** gained `MetricCard`s (replacing a single "Total: 5 · Running: 2 · ..." line) and
a `ProgressBar` (`completed / total` - a test counts as "completed" once it has left `RUNNING`), a
highlighted style for `FAILED` rows, and a native `<details>/<summary>` per test row for its failure
`detail` text - "expandable" for free, with no ARIA to hand-roll, since disclosure widgets are a
built-in HTML element with native keyboard support. Both this page's and `RunsTable`'s date columns
now render through `src/domain/datetime.ts`'s `formatLocalDateTime` (a fixed `"en-US"`/`dateStyle:
"medium"`/`timeStyle: "short"` format, not the viewer's own raw-ISO string) instead of the raw ISO
timestamp - deliberately not the viewer's actual locale, so date-formatting test assertions stay
deterministic across machines; `vite.config.ts` also pins `TZ=UTC` for the test process specifically
so those assertions don't depend on the CI runner's own default timezone (production is
unaffected - a real browser always uses the viewer's actual timezone for `toLocaleString`).

Accessibility beyond the pieces already covered above: every table has a `<caption>` (visually
hidden via `.visually-hidden` when a preceding heading already makes it redundant for sighted users);
every interactive element is a real `<button>`/`<a>`/`<select>`/`<input>`/`<details>`, never a `<div>`
with a click handler, so keyboard operability and correct ARIA semantics come from the browser, not
hand-rolled `tabIndex`/`onKeyDown` plumbing; the connection banner and health indicator both use
`role="status"` (implicitly `aria-live="polite"`) so only that region's own text change is announced,
not the whole page re-announcing on every re-render.

## Architecture (current)

```
src/
├── api/
│   ├── generated/          # typed-openapi output - generated, not hand-edited (see above)
│   ├── runner-api.ts        # hand-written wrapper - imports api/generated/
│   ├── problem-detail.ts     # RunnerApiError
│   └── query-keys.ts          # TanStack Query key shapes
├── app/
│   ├── AppShell.tsx      # layout route: header/health/sidebar nav wrapping every page
│   └── ...                # router, React Query client, provider composition
├── components/
│   └── ui/            # Button, StatusBadge, Alert, EmptyState, LoadingSkeleton, PageHeader,
│                        # MetricCard, ProgressBar - the shared design-tokens-based kit (see above)
├── domain/
│   ├── runner-event.ts   # hand-written Zod contract for SSE events (see above)
│   ├── run.ts              # Environment/Suite/RunStatus types derived from generated types
│   ├── duration.ts           # formatDuration / runDurationMs
│   └── datetime.ts             # formatLocalDateTime
├── features/
│   ├── run-launch/        # RunLaunchForm - the /runs "start a run" form
│   ├── run-list/           # RunListPage + RunsTable (search/filter/sort) - /runs
│   ├── run-details/         # RunDetailsPage - /runs/:runId, live via useRunEventStream
│   └── event-stream/         # pure reducer + EventStreamClient transport (real/fake) + hook
├── styles/              # tokens.css, reset.css, global.css (see Design system & UI above)
├── test/
│   ├── setup.ts          # Vitest setup: jest-dom matchers, MSW server lifecycle
│   └── msw/               # default request handlers + setupServer instance
└── main.tsx
```

`App` accepts an optional `router` prop so tests can render against an isolated
`createMemoryRouter` instead of sharing the app's singleton `createBrowserRouter` navigation state.

Deliberately not using Redux/Zustand: TanStack Query owns REST/server state, the reducer above owns
a single run's SSE event stream, and local component state handles forms/UI.
