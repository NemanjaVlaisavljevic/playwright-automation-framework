# Portfolio demo script

A fixed, repeatable walkthrough of the runner/dashboard system for showing to an employer - nine
steps, no manual code changes, no editing of test files or config between runs. Every step below
was live-verified against a real `runner-service` + real `runner-dashboard` (`npm run dev`) + a
real local Docker RBP stack, not just reasoned about - see "Verified" at the end. Steps 1, 3-4 and
6-9 are also each covered by a dedicated, CI-gated `dashboardE2eTest` scenario (named per step
below), so this script isn't just a one-off manual walkthrough - it's the same behavior CI proves
on every `dashboard-e2e.yml` run.

**Reproducible when the prerequisites below are actually met** - a healthy local Docker RBP stack
for step 2, network reachability to the public
[Restful Booker Platform](https://automationintesting.online/) target for step 5, both processes
below actually running. Given that, the script's own *behavior* (which UI elements appear, how the
filters narrow the table, what the deep link reveals) is deterministic and repeats identically.
The exact *values* shown on screen each time - run IDs, timestamps, and durations - will differ
run to run; where this document quotes a duration or count from a real session, treat it as an
illustrative example, not a number the script guarantees.

## One-time setup

Full detail (including the two-terminal walkthrough and Environment/Suite table) is in the root
[README](../README.md#run-it-locally-in-5-minutes) - summarized here for convenience:

- **JDK 21** and **Node 24** (`runner-dashboard/.nvmrc` pins the latter) installed.
- One-time: `./gradlew.bat playwrightInstall` (macOS/Linux: `./gradlew playwrightInstall`) -
  installs the Chromium build every suite run launches.
- `cd runner-dashboard && npm ci` - installs the dashboard's own dependencies once.
- For step 2 (`LOCAL`/`JOURNEY`) only: **Git** and **Docker Desktop with Compose support**, since
  `localSutUp` fetches and builds the target application from source the first time.

## Prerequisites (each run of the demo)

Two processes, plus the local stack for step 2 only. macOS/Linux: drop the `.bat` from every
`gradlew.bat` command below.

```bash
# Terminal 1 - backend
./gradlew.bat :runner-service:bootRun

# Terminal 2 - dashboard
cd runner-dashboard
npm run dev
```

For step 2 (`LOCAL`/`JOURNEY`), the local Docker RBP stack must already be up and healthy -
started by hand, not by the runner itself:

```bash
./gradlew.bat localSutUp
./gradlew.bat localSutHealth
```

If the stack isn't up, `LOCAL`/`JOURNEY` still launches from the dashboard, but the underlying
Gradle task fails fast at its `localSutVerifyRunning` precondition instead of running any tests -
skip straight to step 5 (`PUBLIC`/`FIXTURE`, no Docker needed) if a live LOCAL/JOURNEY run isn't
wanted for a given demo.

## The script

1. **Open `/runs`.** Shows the "Start a run" form (Environment/Suite dropdowns, a `Run` button)
   and the run history table beneath it, empty or populated depending on prior runs.
2. **Launch `LOCAL` + `JOURNEY`.** Selecting `LOCAL` in the Environment dropdown narrows the Suite
   dropdown to exactly `JOURNEY` (the only combination `LOCAL` allows today) - no manual typing,
   this comes straight from the backend's `/api/v1/capabilities` response. Click `Run`; the
   dashboard navigates straight to `/runs/:runId` for the new run. (`SuiteCommandFactoryTest`/
   `RunRequestValidatorTest` prove the routing; this step itself is the live, in-browser proof of
   the same LOCAL/JOURNEY path documented in `docs/RELEASE_CANDIDATE.md`'s C5.1 acceptance matrix.)
3. **Open details while the tests run.** Already there from step 2's navigation - the run is
   `RUNNING`, with a live `Progress` panel (Total/Running/Passed/Failed/Skipped/Aborted/
   Interrupted counts) updating over SSE as the journey suite executes.
4. **Show Live Focus and steps, live.** The "Active now" panel beneath Progress lists every
   currently-running test and its most recently reported step name (e.g. "Provision an existing
   booking"), updating in real time without a page refresh - this is the `Steps` API's
   step-by-step reporting surfacing live, not just a pass/fail at the end. (Covered by
   `TestResultsFiltersE2eTest.liveFocusRevealsAndFocusesATestCurrentlyHiddenByAFilter` and the
   `RunLifecycleE2eTest` family.)
5. **Then launch `FIXTURE`, while `LOCAL`/`JOURNEY` is still running.** Go back to `/runs`
   (`PUBLIC` environment, `FIXTURE` suite) and click `Run` without waiting for step 2's run to
   finish. `runner-service` serializes every run - of any environment - through a single-worker
   queue (`RunService`'s `ThreadPoolExecutor(1, 1, ...)`; there is no per-environment lock), so the
   new run appears immediately in the history table with status **`QUEUED`**, not `RUNNING` - a
   real, correctly-modeled scheduling state, not a bug. The moment `LOCAL`/`JOURNEY` reaches a
   terminal status, `FIXTURE` automatically flips from `QUEUED` to `RUNNING` with no further
   interaction - this is worth calling out explicitly to an audience as proof the runner's queue
   handles overlapping requests safely and predictably, one at a time. `FIXTURE` itself runs two
   deterministic, on-demand tests: `StepDrilldownFixtureTest` (always fails its third step on
   purpose) and `CancelDuringStepFixtureTest` (blocks mid-step, then resolves on its own if
   nothing cancels it) - both reach a terminal state within roughly 15 seconds of actually
   starting, no manual cancellation needed. (If a simpler, single-run-at-a-time walkthrough is
   preferred for a given audience, just wait for `LOCAL`/`JOURNEY` to finish first - `FIXTURE` then
   goes straight to `RUNNING` as usual.)
6. **Open the failed test.** Click the disclosure arrow next to "Deliberately fails its third
   step, for step/failure/artifact drill-down verification" (`FAILED`) to expand its three steps -
   two `PASSED`, one `FAILED` ("intentionally fail this step").
7. **Show the exception, screenshot, and trace.** The failed step's own row carries the real
   exception (`org.opentest4j.AssertionFailedError: [deliberate fixture failure - not a real
   defect]`, expandable via "View full detail"), a screenshot thumbnail (opens full-size), and a
   "Download trace" link - all scoped to that specific step, not just "the test failed somewhere".
   (`StepDrilldownE2eTest.showsStepFailureDetailAndArtifactsScopedToTheFailingStep` is the CI
   proof of exactly this.)
8. **Copy the deep link and open it in a new tab.** Click "Copy link" on the failed step's own row
   - copies an absolute URL (`.../runs/<runId>?testId=...&stepId=...`) to the clipboard. Paste it
   into a brand-new tab: the run loads, the failed test's steps auto-expand, and the exact failed
   step scrolls into view and receives focus, with its screenshot/trace links already visible -
   proving the link is genuinely shareable, not session-bound. (`DeepLinkE2eTest.
   copiedStepLinkOpensInANewTabAndRevealsTheRealFailedStep` is the CI proof.)
9. **Show the `Problems` and `Has artifacts` filters.** Back on the run's Tests section: the
   Status dropdown's `Problems` option narrows the table to only non-passing tests (here, 1 of 2 -
   the failed one); independently, the Evidence dropdown's `Has artifacts` option narrows to only
   tests carrying a screenshot/trace (also 1 of 2, for the same test, since the passed fixture
   test captured nothing). Both filters combine with the free-text search and are proven by
   `TestResultsFiltersE2eTest.searchAndStatusAndEvidenceFiltersNarrowTheVisibleTests`.

## Why this counts as reproducible

Every step above is driven entirely by already-shipped UI (the launch form's allowlisted
Environment/Suite dropdowns, the Tests table's fixed filter options, the per-row/per-step Copy
link buttons) against already-shipped backend behavior (the single-worker run queue and its
`QUEUED`/`RUNNING` state machine, SSE live progress, REST-served history and artifacts) - nothing
in this script requires touching a test file, a config value, or any source at all between runs.
With the prerequisites met, re-running it reproduces the same nine *observations* every time - the
same UI states, the same filter-narrowing behavior, the same deep-link reveal - even though the
exact run IDs, timestamps, and durations differ on each run.

## Verified

Live-walked end to end in a real browser (Claude-in-Chrome against a real `runner-service` +
`npm run dev` dashboard + the already-running local Docker RBP stack), 2026-09-05 (durations below
are the actual numbers from that session - illustrative, not guaranteed to repeat exactly):
- Step 1: `/runs` rendered the launch form and history table as described.
- Step 2: selecting `LOCAL` narrowed Suite to `JOURNEY` alone; `Run` launched a real journey run
  against the local stack, landing on its own `/runs/:runId`.
- Steps 3-4: `Progress` and "Active now" updated live while `LOCAL`/`JOURNEY` was still running,
  showing real in-flight step names.
- Step 5, corrected after review: submitted `PUBLIC`/`FIXTURE` (via the real `POST /api/v1/runs`
  endpoint, matching what the launch form itself calls) while `LOCAL`/`JOURNEY` (runId
  `83ac354d-...`) was still `RUNNING` - the new run (runId `84d2907f-...`) came back and stayed
  `QUEUED`, visibly rendered as such in both the run's own detail page and the `/runs` history
  table (`QUEUED` above `RUNNING` in the same table, screenshot-verified), for as long as
  `LOCAL`/`JOURNEY` kept running. The instant `LOCAL`/`JOURNEY` reached `SUCCEEDED` (41s), the
  `FIXTURE` run automatically flipped to `RUNNING` with zero manual intervention, then reached
  2/2 tests complete (1 passed, 1 failed) about 15s later. An earlier draft of this document
  incorrectly claimed the two runs progress "concurrently" thanks to an "environment-scoped
  lock" - there is no such lock; `RunService` uses one single global single-worker executor
  regardless of environment, and this corrected script/verification reflects that.
- Steps 6-7: the failed test's steps expanded to show the real exception text, a screenshot
  thumbnail, and a working trace download link.
- Step 8: the failed step's "Copy link" button was clicked for real (its underlying
  `navigator.clipboard.writeText` call was captured, not the visible clipboard - Chrome does not
  treat a CDP-dispatched click as a trusted gesture for clipboard permission purposes, the same
  reason `DeepLinkE2eTest` itself calls `context.grantPermissions(...)` first); the captured URL
  contained real `testId=`/`stepId=` query parameters and, opened in a genuinely separate browser
  tab, auto-revealed and focused the exact failed step.
- Step 9: `Problems` alone, then (after clearing) `Has artifacts` alone, each independently
  narrowed the Tests table from 2 to 1 row - the same failed test in both cases.

No code was written or modified to make any of this work - every element and endpoint already
existed from prior phases (C1-C4, Faza A/B).
