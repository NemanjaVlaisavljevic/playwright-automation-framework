# Release Candidate — C5

C5 adds no new functionality. It proves that everything already built works together,
reproducibly, from a clean environment, with documentation a stranger could follow. This file is
the living record of that proof, filled in section by section as each C5 sub-phase closes:

- **C5.1 — Acceptance matrix** (this file, below): does real evidence already exist for every
  scenario an RC needs, or is there a real gap?
- **C5.2 — Clean-environment reproducibility** (this file, below): done.
- **C5.3 — Three CI proofs on one commit** (this file, below): done.
- **C5.4 — Portfolio README**: done - see the root [`README.md`](../README.md).
- **C5.5 — Portfolio demo script**: done - see [`docs/PORTFOLIO_DEMO.md`](PORTFOLIO_DEMO.md).
- **C5.6 — RC sign-off**: done - see below. **Decision: GO for portfolio release candidate**, no
  `v1.0.0-rc.1` tag yet (deferred to Phase D, see below).

## C5.1 — Acceptance matrix

Every row below was checked against the actual current codebase on 2026-09-04 (test file/method
identified by name, not assumed from memory) — not written from recollection of what was built.
Where a row's real coverage differs from what a casual read of the plan might imply, the **Napomena**
column says so plainly rather than papering over it.

| Oblast | Scenario | Dokaz (file : method) | CI workflow | Napomena |
|---|---|---|---|---|
| Launch | PUBLIC/SMOKE uspešan run | `RunLifecycleE2eTest.launchesASmokeRunAndReachesATerminalStatus` | `dashboard-e2e.yml` | Real end-to-end: real backend, real dashboard build, real Chromium. |
| Local execution | LOCAL/JOURNEY, svih 6 klasa | Combined proof, four parts (see decision below) | `local-sut.yml` (`localTest`) | **Decided, not a gap.** `localTest` is the broader gate and already runs all six journey classes against the same local stack in CI — a separate `localJourneyTest` CI step would repeat the same tests, lengthen the workflow, and add flake surface with no new coverage. See "LOCAL/JOURNEY combined proof" below for the four-part reasoning and the fresh local run. |
| Live results | REST + SSE + stepovi | `StepDrilldownE2eTest.showsStepFailureDetailAndArtifactsScopedToTheFailingStep`, `RunLifecycleE2eTest` | `dashboard-e2e.yml` | Real. |
| Failure | exception + screenshot + trace | `StepDrilldownE2eTest.showsStepFailureDetailAndArtifactsScopedToTheFailingStep` | `dashboard-e2e.yml` | Real - asserts exception class/message text and that both artifact links actually resolve (200, correct content-type). |
| Cancel | aktivni test/korak → INTERRUPTED | `CancelE2eTest.cancellingARunningRunReachesCancelledAndNotSomethingElse`, `.cancellingDuringAnActiveStepMarksTheTestAndActiveStepInterrupted` | `dashboard-e2e.yml` | Real - the second method specifically asserts step-level `INTERRUPTED` reconciliation. |
| Recovery | reconnect + fresh replay | `GapReplayE2eTest.recoversFromAGapWithExactlyOneFreshReplayAttempt`, `ReconnectE2eTest.nativeEventSourceReconnectsAfterAMidRunDrop` | `dashboard-e2e.yml` | Honest caveat preserved from these tests' own Javadoc: both are "browser-level SSE transport/recovery integration tests" - a real backend, but a synthesized dropped/gapped stream via Playwright network routing, not an actual killed backend process. (A real backend kill is `BackendUnavailableE2eTest`, a different scenario.) |
| Navigation | deep link u novom tabu/reload | `DeepLinkE2eTest.copiedStepLinkOpensInANewTabAndRevealsTheRealFailedStep` | `dashboard-e2e.yml` | Real - genuinely new `Page` via `BrowserContext.newPage()`, real clipboard permissions. |
| UX | filteri, Live Focus, long content | `TestResultsFiltersE2eTest.searchAndStatusAndEvidenceFiltersNarrowTheVisibleTests`, `.liveFocusRevealsAndFocusesATestCurrentlyHiddenByAFilter`, `LongContentE2eTest.longTestStepAndFailureContentNeverCausesHorizontalPageScroll` | `dashboard-e2e.yml` | Real, all three named concerns covered. |
| Accessibility | axe + keyboard walkthrough | `AccessibilityE2eTest.runsListPageHasNoAxeViolations`, `.runDetailsPageHasNoAxeViolationsWhileLiveAndAfterFailureWorkspaceExpands`, `KeyboardNavigationE2eTest` (see below) | `dashboard-e2e.yml` | Real, both halves now automated: axe covers static ARIA/contrast/semantics (including a genuinely-`RUNNING` state with Live Focus live), `KeyboardNavigationE2eTest` covers real keyboard operability (Tab order through filters, Enter/Space on disclosures and Live Focus items, a representative subset's focus-indicator style - see below for exactly which and why not all). Exact focus-ring pixel appearance stays a manual/visual concern, covered once during C4.6.1. |
| Responsive | 320/768/1440 | `ResponsiveE2eTest.runsListPageNeverScrollsHorizontally`, `.runDetailsPageWithFailureWorkspaceNeverScrollsHorizontally` | `dashboard-e2e.yml` | Real - a genuine bug (document-level horizontal scroll, fixed with `contain: paint`) was caught by this exact test earlier in C4.6. |
| Scale | 100 testova/400 koraka | `LargeRunE2eTest.aHundredTestsWithHundredsOfStepsRendersCorrectlyWithNoErrors`, `RenderPerformanceE2eTest.renderingAHundredTestsAndFilteringThemStaysWellWithinBudget` | `dashboard-e2e.yml` | Real. |
| Contract | OpenAPI snapshot/generation | `npm run api:check:snapshot` (no backend), `npm run api:check:contract` (live backend) | `dashboard-quality.yml` (`frontend-quality`, `openapi-contract` jobs) | Real, both halves covered on their own dedicated workflow. |

### LOCAL/JOURNEY combined proof (decided, not a gap)

`localJourneyTest` (a dedicated Gradle task, `baseUrl=http://localhost`, `dependsOn
localSutVerifyRunning`) exists specifically as the dashboard-facing `LOCAL`+`JOURNEY` entry point.
It is **not** an identical task to `localTest` - the two select different tags and run different
scopes: `localTest` runs `includeTags 'regression'` (all 32 read-only + mutation tests against the
local stack, `excludeTags 'fixture'`), while `localJourneyTest` runs `includeTags 'journey'`
(exactly the six journey classes, same `excludeTags 'fixture'`). The six journey classes happen to
pass `localTest`'s filter too, since every journey test also carries the mandatory `regression` tag
(see `AutomationExtension`) - so `localTest` already exercises them as part of its broader run, not
because it targets `journey` specifically. Adding `localJourneyTest` to `local-sut.yml` as a second
CI step would therefore be a **re-execution of a subset `localTest` already covers**, not a run of
identical tests under an identical filter - the outcome (no new coverage, added CI time, added flake
surface) is the same either way, so the decision stands, but described accurately rather than as a
literal duplicate. The real requirement - "does LOCAL+JOURNEY actually work, end to end, including
the dashboard's own routing to it" - is met by four independent pieces of evidence instead of a
redundant CI task:

1. **`local-sut.yml` → `localTest`** (CI, runs today) proves all six journey classes themselves pass
   against the real local stack - `BookingJourneyTest`, `AdminRoomManagementJourneyTest`,
   `AdminBookingManagementJourneyTest`, `AdminMessageManagementJourneyTest`,
   `AdminReportJourneyTest`, `FeaturedRoomParityTest`.
2. **`SuiteCommandFactoryTest.mapsLocalJourneyToItsOwnDedicatedGradleTask`** and
   **`RunServiceTest.submitPassesTheSelectedLocalEnvironmentThroughToTheLaunchedCommand`** (unit
   tests, run on every `test`/CI invocation) prove the *routing* - that selecting `LOCAL`+`JOURNEY`
   in the dashboard actually resolves to the `localJourneyTest` command, not `journeyTest` or
   anything else - independent of whether that command's own test classes pass, which (1) already
   covers.
3. **A manual dashboard run** (this session, live browser check during Faza C1.5) confirmed the
   complete path once, end to end: selecting `LOCAL` in the Environment dropdown narrows Suite to
   exactly `JOURNEY`, and launching it drives a real `runner-service` process through
   `localJourneyTest` with live SSE-rendered progress in the UI.
4. **`localJourneyTest` itself, re-run fresh today** (2026-09-04, local stack already up, 9h
   uptime) against the real local Docker stack, to have a current result in this document rather
   than relying on session memory:

   ```
   ./gradlew.bat localJourneyTest --rerun
   ```

   ```
   localSutVerifyRunning: all services UP ([auth, booking, room, report, branding, message])
   localSutVerifyRunning: assets front door OK (...)

   FeaturedRoomParityTest > Homepage renders the first three API rooms as booking actions PASSED
   AdminBookingManagementJourneyTest > Admin can view, edit, and delete a room's booking through the admin UI PASSED
   AdminMessageManagementJourneyTest > Admin can view and delete a message through the admin UI (local target only) PASSED
   BookingJourneyTest > Guest can complete a booking for a freshly created, isolated room PASSED
   AdminReportJourneyTest > Report includes the booking with correct check-in and check-out dates PASSED
   AdminRoomManagementJourneyTest > Admin can create, edit, and delete a room through the admin UI PASSED

   BUILD SUCCESSFUL in 46s
   ```

`localJourneyTest` remains a deliberate local/demo-only task - never a second CI gate for the same
coverage `localTest` already provides.

### Other gaps found while preparing this matrix

1. ~~`quality-gate.yml`'s evidence-upload artifact name was missing `run_attempt`~~ **Fixed** -
   changed to `automation-evidence-${{ github.run_id }}-${{ github.run_attempt }}`, matching
   `dashboard-e2e.yml` and `local-sut.yml`. Required before the C5.3 CI run.
2. **Accessibility's keyboard half had zero automated coverage** - addressed with a new
   `KeyboardNavigationE2eTest` (see below), not left as a documented gap.

**Review round on this matrix itself (2026-09-04) - 1 P1 + 2 P2, all fixed:**

1. ~~[P1] The Live Focus wait in `AccessibilityE2eTest` used Playwright's 30s default timeout, and a
   real run hit it~~ **Fixed.** Added `RunDetailsPage.waitForLiveFocusStep(name, Duration)` and used
   an explicit 75s budget in both `AccessibilityE2eTest` and `KeyboardNavigationE2eTest` - a cold
   Gradle/JUnit start (or a prior test's queued backend cleanup) can burn a meaningful chunk of the
   fixed 8s active window before it even begins, and the reported first failure was real proof this
   isn't just theoretical. Confirmed afterward with a fresh full 21-scenario suite run.
2. ~~[P2] The focus-indicator assertion didn't cover every element the doc claimed~~ **Resolved by
   narrowing the documentation**, not by forcing every element through a style check: attempting to
   verify a real focus-indicator on the Live Focus button, the test disclosure toggle, and the
   failure-detail `<summary>` surfaced a genuine constraint - Chromium's `:focus-visible` heuristic
   does not fire for a script-driven `Locator#focus()` call, only a real `Tab` keypress (or a click
   on a text input), so a style assertion on those three would either be meaningless or require
   exactly the fragile full-page Tab chain this test was designed to avoid. See "Keyboard navigation
   regression gate" below for exactly which elements are style-checked and why the rest are
   functionally-checked only.
3. ~~[P3] `docs/RELEASE_CANDIDATE.md` described `localJourneyTest` as a "superset-free duplicate...
   same tags" of `localTest`~~ **Reworded for technical accuracy** - `localTest` selects
   `includeTags 'regression'` (all 32 tests), `localJourneyTest` selects `includeTags 'journey'`
   (six classes); the six pass `localTest`'s filter only because every journey test also carries the
   mandatory `regression` tag, not because `localTest` targets `journey`. The decision (don't add a
   second CI step) is unchanged - only the reasoning is now precise.

Every other row has real, currently-passing, correctly-scoped test evidence, and the one remaining
honest caveat (Recovery's synthetic-transport scope) was already accurately described in the tests'
own Javadoc - this matrix just makes it visible at a glance rather than requiring a reader to open
each test file.

### Keyboard navigation regression gate (added 2026-09-04)

Axe (the automated half of the Accessibility row) checks static ARIA/contrast/semantic properties -
it does not exercise actual keyboard operability. `KeyboardNavigationE2eTest` closes that gap with a
small, deliberately non-fragile set of real keyboard-driven interactions (real `Tab`/`Enter`/`Space`
key presses via Playwright, not a synthetic focus-order snapshot of the whole page, which would
break on every unrelated layout change):

- `Tab` moves through the C4.4 filter toolbar (search → status → evidence → clear filters) in
  logical order.
- `Enter` on a test's disclosure toggle expands its step list.
- `Enter` and `Space` on a Live Focus panel item each focus that test's own row in the Tests table
  (verified independently, not just one of the two keys).
- A failure detail's native `<details><summary>` disclosure opens via `Enter` on the keyboard alone.
- A **representative** subset of the elements above - not all of them - also gets a real
  focus-indicator style assertion: the search input, the status/evidence filters, and the clear
  filters button (all reached via a genuine `Tab` keypress or a click on a text input) check a
  visible `box-shadow`; the Live-Focus-revealed table row checks a background-color change instead
  (its own deliberate exception - see `RunDetailsPage.module.css`'s `.focusableRow:focus` comment).
  **Not** representative-checked: the Live Focus button itself, the test disclosure toggle, and the
  failure-detail `<summary>` - Chromium's own `:focus-visible` heuristic does not fire for a
  script-driven `Locator#focus()` call (only a real `Tab` keypress, or a click on a text input), so
  proving a style assertion on every element here would require exactly the fragile full-page
  Tab-order chain this test deliberately avoids. Their keyboard *activation* is still fully verified
  above - only the focus-ring *style* check is narrowed to the representative subset.

Exact pixel appearance of the focus ring stays a manual/visual concern (already covered once,
manually, during C4.6.1) - this test's job is only to guarantee the underlying keyboard operability
never silently regresses.

## C5.2 — Clean-workspace reproducibility (2026-09-04)

Proves the project builds and passes entirely from a clean copy of the current source - not a fresh
`git clone` (the C4/C5 work isn't committed yet - see the standing git note above), but a byte-exact
simulation of "commit everything right now and clone it": every tracked file (`git ls-files`) plus
every untracked-but-not-gitignored file (`git ls-files --others --exclude-standard`), copied to a
different absolute path with zero `build/`, `node_modules/`, `dist/`, `coverage/`, or `.gradle/`
carried over.

**Scope, precisely**: this is a **clean-workspace** proof (no stale local build outputs, no
absolute-path/IDE/pre-started-server dependency) - not a cold-host proof. Host-level caches were
*not* wiped: the shared Gradle user home (`~/.gradle`), the npm cache, and the Playwright browser
cache (`~/.cache/ms-playwright` or platform equivalent) were left exactly as they were, so
`playwrightInstall` and every dependency resolution step could legitimately reuse
already-downloaded artifacts. True cold-host package installation (no pre-warmed caches at all) is
what C5.3's real GitHub Actions runners prove instead - each `workflow_dispatch` run starts from a
genuinely fresh runner with nothing cached.

### Reproducing this proof

The exact commands used, so a stranger (or a future session) can redo this without guessing:

```bash
# 1. Build the exact file list a fresh clone of the current working tree would have -
#    tracked files plus untracked-but-not-gitignored ones (new work not yet committed).
git ls-files > /tmp/clean-checkout-filelist.txt
git ls-files --others --exclude-standard >> /tmp/clean-checkout-filelist.txt

# 2. Copy exactly that file list into a new directory at a different absolute path.
DEST=/path/to/clean-checkout
while IFS= read -r f; do
  mkdir -p "$DEST/$(dirname "$f")"
  cp "$f" "$DEST/$f"
done < /tmp/clean-checkout-filelist.txt

# 3. Restore real git metadata - a genuine checkout always has .git, and the OpenAPI
#    drift-check script (check-git-clean.mjs) needs it to run `git status --porcelain`.
cp -r .git "$DEST/.git"

# 4. Confirm no build-output directories leaked in (should print nothing).
find "$DEST" -maxdepth 3 \( -iname build -o -iname node_modules -o -iname dist \
  -o -iname coverage -o -iname .gradle \) -type d
```

```powershell
# 5. Confirm the ports dashboardE2eTest's own backend/dashboard will bind are free
#    *before* running it - otherwise a "starts its own server" claim proves nothing.
netstat -ano | Select-String ":8080 |:5173 " | Select-String "LISTENING"
# (expect no output)
```

Then run the checklist below from inside `$DEST`.

| Step | Command | Result |
|---|---|---|
| Java 21 / Node 24 | `java -version`, `node -v` | `21.0.6`, `v24.20.0` - matches the stated requirement |
| `npm ci` (not `npm install`) | `npm ci` in `runner-dashboard/` | 250 packages installed clean, 0 vulnerabilities |
| Chromium install | `./gradlew.bat playwrightInstall` | `BUILD SUCCESSFUL` |
| Java/module tests | `./gradlew.bat spotlessCheck test :runner-contract:test :runner-listener:test :runner-service:test` | All green |
| Frontend gate | `npm run check` | 254/254 tests, 97.33%/94.74%/97.2%/97.55% stmt/branch/func/line coverage - identical to the original checkout's numbers |
| `dashboardE2eTest` | `./gradlew.bat dashboardE2eTest` | 21/21 scenarios green |
| Orphan processes | `Get-CimInstance Win32_Process` (java/node/chrome) after the run | Only the Gradle daemon and the user's own unrelated desktop Chrome/Node.js tooling processes - nothing left behind by this run |
| OpenAPI drift (snapshot only) | `npm run api:check:snapshot` | `Clean: src/api/generated` |
| OpenAPI drift (live backend) | started `runner-service-1.0.0-SNAPSHOT.jar` from the clean copy, then `npm run api:check:contract` | `Clean: openapi/runner-api.json, src/api/generated` - the committed snapshot and generated client both match a real live backend's `/v3/api-docs` exactly |

**The three specific risks called out - all confirmed absent:**
- **Local absolute paths**: every step above ran successfully at
  `C:\Users\...\clean-checkout-c52`, a completely different path from the original repo's
  `C:\Users\...\playwright-automation-framework` - if anything had hardcoded the original path,
  every one of these would have failed. Additionally grepped `build.gradle`, `settings.gradle`,
  `gradle.properties`, `runner-dashboard/package.json`, and `runner-dashboard/vite.config.ts` for
  literal `C:\Users`/`/Users`/`/home` paths - none found.
- **IntelliJ dependency**: `.idea/.gitignore`, `.idea/gradle.xml`, `.idea/misc.xml`, and
  `.idea/vcs.xml` *are* tracked in git, so they were part of the copy (a git-file-list-based copy
  really is byte-exact - it does not selectively drop tracked files). The independence proof is not
  their absence, it's that IntelliJ itself was never launched at any point in this checklist -
  every step ran via plain `gradlew.bat`/`npm` CLI commands, and grepping the build files for any
  reference to `.idea` found nothing that would make a build step read or require it.
- **A previously-started server**: ports 8080/5173 were confirmed free *before* `dashboardE2eTest`
  ran (see step 5 of the reproduction commands above), and the suite started its own backend and
  dashboard build from scratch (the same `DashboardProcess` safety check that refuses to start when
  something is already listening, proven multiple times earlier this project, guarantees this isn't
  accidentally reusing an existing instance).

No fixes were needed - every step passed on the first attempt. **C5.2 is now closed.**

### Review round on this section (2026-09-04) - 1 P1 + 2 P2 + 1 P3, all fixed

1. ~~[P1] Claimed both "every `git ls-files` file was copied" and "`.idea/` never existed" - these
   contradict each other, since `.idea/.gitignore`/`gradle.xml`/`misc.xml`/`vcs.xml` are genuinely
   tracked~~ **Fixed.** The copy itself was correct (verified: those four files really were
   present in the clean copy) - only the doc's own claim about `.idea/` was wrong, caused by
   checking with plain `ls` (which hides dotfiles) instead of `ls -a`. Corrected to describe what
   was actually verified: IntelliJ was never launched, and nothing in the build reads `.idea/`.
2. ~~[P2] The exact reproduction commands weren't recorded, only the result~~ **Fixed** - added the
   "Reproducing this proof" section above with the literal commands used for the file-list copy,
   the `.git` restore, the build-output-absence check, and the free-port check.
3. ~~[P2] "Clean-environment" implied a cold host, but Gradle/npm/Playwright caches were never
   wiped~~ **Fixed** - retitled to "Clean-workspace reproducibility" and added the explicit "Scope,
   precisely" paragraph above naming which caches were retained and pointing at C5.3's real CI
   runners as the actual cold-host proof.
4. ~~[P3] One paragraph said "22-scenario suite run" while the table says 21/21~~ **Fixed** - the
   real count is 21 (confirmed against both the source `@Test` methods and the JUnit XML results);
   changed to "21-scenario."

## C5.3 — Three CI proofs on one commit (2026-09-04)

All three named workflows run green on the exact same commit,
**`a9dd6120d3b74ae45ef427752b781777254595b4`** ("Finishing Faze C5.2") - independently confirmed via
GitHub's public REST API (`GET /repos/.../actions/runs/{id}` and its `/jobs` sub-resource,
unauthenticated, read-only), not just eyeballed in the UI. `quality-gate.yml` triggers on `push` to
`master` and fired automatically from this commit. `dashboard-e2e.yml` and `local-sut.yml` both
*also* have a weekly `schedule` trigger alongside `workflow_dispatch` (Thursday 05:43 UTC and Monday
04:17 UTC respectively) - they are not dispatch-only workflows, they just weren't due to fire on
their own yet. These two specific runs were triggered manually via `workflow_dispatch` on `master`
(which, since nothing else was pushed in between, dispatched against this same SHA) - confirmed by
checking each run's own `head_sha` via the API rather than assuming the dispatch landed on the
intended commit.

**A fourth gate is also green on this exact commit, without a new run being needed**:
`dashboard-quality.yml` (Dashboard Quality Gate) auto-triggered on the same `push` as
`quality-gate.yml` and completed successfully - both its `frontend-quality` job (coverage, lint,
build) and its `openapi-contract` job (snapshot check + live-backend contract check) passed. Since
C5.1's acceptance matrix already cites this exact workflow as the Contract row's own CI evidence,
it belongs alongside the three the user named, not left out of this section.

| Workflow | Run | Trigger | Conclusion | Artifact(s) |
|---|---|---|---|---|
| `quality-gate.yml` (Quality Gate) | [`33913557307`](https://github.com/NemanjaVlaisavljevic/playwright-automation-framework/actions/runs/33913557307) | `push`, attempt 1 | success | `automation-evidence-33913557307-1` |
| `dashboard-quality.yml` (Dashboard Quality Gate) | [`33913557312`](https://github.com/NemanjaVlaisavljevic/playwright-automation-framework/actions/runs/33913557312) | `push`, attempt 1 | success | none - both jobs' upload steps (`Upload coverage and test report`, `Upload backend log`) are `if: failure()` and correctly show `skipped`, since nothing failed |
| `dashboard-e2e.yml` (Dashboard E2E) | [`33913907723`](https://github.com/NemanjaVlaisavljevic/playwright-automation-framework/actions/runs/33913907723) | `workflow_dispatch`, attempt 1 | success | `dashboard-e2e-junit-33913907723-1`, `dashboard-e2e-html-report-33913907723-1`, `dashboard-e2e-process-logs-33913907723-1` |
| `local-sut.yml` (Local SUT Regression) | [`33913918735`](https://github.com/NemanjaVlaisavljevic/playwright-automation-framework/actions/runs/33913918735) | `workflow_dispatch`, attempt 1 | success | `local-sut-evidence-33913918735-1` |

**`dashboard-e2e.yml` and `local-sut.yml` ran concurrently, deliberately** - both were dispatched at
the same time. Each declares its own distinct `concurrency` group (`dashboard-e2e-${{ github.ref }}`
vs. `local-sut-${{ github.ref }}`) and GitHub Actions runs each on its own isolated, ephemeral
`ubuntu-latest` VM, so there is no shared port/state/resource between them - confirmed by both
completing successfully in parallel with no interference.

**Every stated acceptance criterion checked against the real API response, not assumed:**
- **All three (four, counting the Contract gate) green**: confirmed above via each run's own
  `conclusion: success`.
- **JUnit/HTML reports uploaded**: `dashboard-e2e.yml`'s `Upload JUnit report` and `Upload Gradle
  HTML report` steps both `success`, producing the `-junit-`/`-html-report-` artifacts above;
  `local-sut.yml`'s single `local-sut-evidence-*` artifact bundles its own equivalent reports.
- **Process logs uploaded**: `dashboard-e2e.yml`'s `Upload process logs` step `success`
  (`dashboard-e2e-process-logs-*`).
- **Failure-artifacts step runs with `if: always()`**: `dashboard-e2e.yml`'s `Upload failure
  artifacts (screenshots, traces, videos)` step reported `success` even though nothing failed (0
  scenarios failed, so it legitimately had nothing to upload - the same "no files found, not a
  failure" behavior documented in `RELEASE_EVIDENCE.md` for the equivalent Faza 9 run) - it is not
  in this run's artifact list for that reason, not because the step was skipped.
- **Cleanup safety-net step ran successfully** - `dashboard-e2e.yml`'s dedicated `Clean up any
  leftover processes` step reported `success`, but that alone does not prove no process actually
  survived: its commands are `pkill -f '...' || true` and the step itself carries
  `continue-on-error: true`, so it reports green regardless of whether termination fully succeeded.
  The real proof that nothing was left running comes from the E2E suite's own green result instead:
  `DashboardProcess.stop()` (called from `DashboardE2eEnvironment`'s teardown on every test) throws
  `IllegalStateException` and fails the build if any of its own known backend/dashboard processes
  survive termination - since `dashboard-e2e.yml` completed successfully, those specific processes
  are confirmed gone. This does not amount to a strict "no leftover process of any kind exists on
  the runner" guarantee (that would need a final `pgrep` verifier step that fails the job if
  anything matching survives after cleanup) - worth adding if a stronger CI-level claim is ever
  needed, not implemented here.
- **LOCAL stack always shuts down**: `local-sut.yml`'s `Stop local SUT stack` step `success`; its
  `Dump local SUT diagnostics` step correctly shows `skipped` (that step is failure-only, and
  nothing failed).
- **Artifact names contain `run_id` + `run_attempt`**: every artifact listed above follows
  `<name>-<run_id>-<run_attempt>` - including `automation-evidence-33913557307-1`, the first real
  CI confirmation that the C5.1 review round's `quality-gate.yml` naming fix actually works, not
  just that it reads correctly.

No fixes were needed - all four workflows passed on their first run against this commit.
**C5.3 is now closed.**

### Review round on this section (2026-09-04) - 4 P2/P3, all fixed

1. ~~[P2] Left out the fourth green gate on this same commit~~ **Fixed** - added
   `dashboard-quality.yml` (`run 33913557312`) as a fourth row; no new run was needed since it had
   already gone green on the same `push`.
2. ~~[P2] Described `dashboard-e2e.yml`/`local-sut.yml` as "workflow_dispatch-only"~~ **Fixed** -
   both also carry a weekly `schedule` trigger; reworded to say these *specific runs* were manually
   dispatched, not that the workflows are dispatch-only.
3. ~~[P2] "No leftover processes" overclaimed what the cleanup step's own success actually proves~~
   **Fixed** - reworded to "cleanup safety-net step ran successfully" (verified its commands really
   are `pkill ... || true` under `continue-on-error: true`, so a green result doesn't by itself
   prove termination succeeded) and added the real proof instead: `DashboardProcess.stop()`
   verified to throw on any survivor, making the E2E suite's own green result the actual evidence.
   Noted a stricter `pgrep`-verifier step as a possible future addition, not implemented now.
4. ~~[P3] `dashboard-e2e.yml`'s own header comment still said "7 scenarios"~~ **Fixed** - reworded
   to "the complete dashboard E2E suite" (no fixed number) so it can't go stale again as tests are
   added; the historical "7 scenarios" mentions in `docs/RELEASE_EVIDENCE.md` were left as-is since
   that file is an explicitly point-in-time record of a specific past release, not a living claim.

## C5.4 — Portfolio README (2026-09-04)

Rewrote the root `README.md` to lead with the runner/dashboard system rather than treating it as an
appendix to the original Playwright suite - the differentiator this whole C-phase roadmap has been
building toward. Every fact stated in it was verified against real source before being written, not
assumed:

- **Hero image**: `docs/screenshots/dashboard-step-failure-artifact-drilldown.jpg` (the C4.6.6
  portfolio shot showing the full step/failure/artifact drill-down in one frame).
- **Feature list** (REST-triggered runs, SSE live progress, step-level reporting, artifact-to-step
  attribution, cancellation, transport recovery) - each phrased to match what the acceptance matrix
  above already proves, not aspirational.
- **Architecture diagram** - the user's own React → Spring Boot → Gradle/JUnit/Playwright → Journal
  pipeline, rendered as a GitHub-native Mermaid `flowchart` rather than a plain-text box diagram.
- **"Run locally in 5 minutes"** - the exact `:runner-service:bootRun` + `npm run dev` two-terminal
  flow, with a table naming both the `PUBLIC`/`SMOKE` and `LOCAL`/`JOURNEY` scenarios and the
  `FIXTURE` suite as the fastest path to seeing the failure/artifact drill-down on demand.
- **Test strategy and CI**: names and briefly describes all four workflows (adding
  `dashboard-quality.yml`, the fourth gate the C5.3 review round added), and links to this document's
  own acceptance matrix rather than re-deriving it.
- **Requirements**: JDK 21, Node 24, Docker (LOCAL scenario only) - stated once, up front.
- **Current limitations**: all five items the user named, each verified against real source
  immediately before writing, not carried over from memory - `RunService.activeRuns` really is a
  bare `ConcurrentHashMap` (in-memory, confirmed by reading the field declaration), no
  `spring-boot-starter-security`/`SecurityFilterChain` exists anywhere in `runner-service` (grepped),
  and no `Dockerfile` exists anywhere in the repository (searched) - framed explicitly as RC-stage
  scope decisions Faza D addresses, per the user's own instruction, not hidden defects.

The core suite-focused content was preserved and reorganized - relocated under a new "Automation
suite" section beneath the portfolio-facing material, since it's still accurate and valuable, just
no longer the document's own headline. Outdated fixed-count E2E descriptions (the old "7 scenarios"
list) were replaced with links to this document's own current acceptance matrix instead of being
carried forward as stale prose.

Verified: `./gradlew.bat spotlessCheck` clean (caught and fixed one real formatting violation on the
first pass - `spotlessMisc` also lints Markdown, not just source, and flagged this file; a plain
`spotlessApply` fixed it with no content change), `git diff --check` clean, and every internal link/
anchor checked against the doc's own real heading text (`#quick-start-suite-only-no-dashboard` for
this file's own relocated section, `#architecture-current` for `runner-dashboard/README.md`'s
existing heading) rather than assumed.

### Review round on this section (2026-09-04) - 2 P1 + 3 P2 + 1 P3, all fixed and reverified

1. **[P1] Claimed reconnect replay comes from a durable on-disk journal that survives a service
   restart** - read `FileBackedRunEventJournal` directly: `readAfter`/`latest` serve purely from
   `RunJournal.history`, an in-memory `ArrayList` populated only by `append()`; `journals` is a
   fresh empty map on construction with no code path anywhere that re-hydrates it from the
   `.events.jsonl` files already on disk. Disk writes are real (synchronous, flushed before
   `append` returns) but exist for durability of the record, not for serving replay or surviving a
   restart - confirmed accurate to what the "Current limitations" section already said, which the
   feature list had contradicted. Reworded to: "Events are synchronously persisted to disk, while
   reconnect replay uses the canonical in-memory history for the lifetime of the current service
   instance... Restart recovery and journal re-indexing are planned for Phase D."
2. **[P1] Quick start never mentioned installing the Chromium browser** - a fresh checkout's first
   `PUBLIC`/`FIXTURE`/`SMOKE` run would fail without `./gradlew.bat playwrightInstall` run at least
   once first. Added as an explicit one-time step before the two-terminal walkthrough. Also added
   Git/Docker Compose as `LOCAL`-specific prerequisites, and reworded "5 minutes" to explicitly
   scope to the `PUBLIC`/`FIXTURE` fast path - confirmed via `infra/rbp/README.md`'s own words
   ("slow (several minutes) the first time") that a first local stack build realistically exceeds
   that budget.
3. **[P2] Architecture diagram collapsed a real two-hop flow into one arrow** - read
   `ListenerEventIngestor`'s own Javadoc to confirm the actual path: a JUnit Platform listener and
   the `Steps` API write raw JSONL + an artifact manifest; the runner's ingestor tails that raw
   stream and forwards each validated event into its own separate canonical, sequence-numbered
   journal (the one `FileBackedRunEventJournal` serves replay from). Added a raw-storage node and a
   tail-back edge to the diagram, and replaced "via a JUnit extension" with the more precise "a
   JUnit Platform listener and the `Steps` API."
4. **[P2] "The largest single test class in this repository"** - `dashboardE2eTest` is a Gradle
   source set/test suite, not one class. Independently recounted (17 files via `grep -rl`, 21
   `@Test` methods via `grep -rc`, matching the reviewer's own numbers) before fixing to "the
   largest dedicated test suite in the repository (17 test classes, 21 test methods)."
5. **[P2] `localTest` cited as 27/27** - that number is the automation-foundation-era count from
   `docs/RELEASE_EVIDENCE.md` (2026-08-25), not current. Re-ran `localTest` fresh against the still-up
   local Docker stack rather than trusting old XML on disk: **32/32 passed, 0 failures/errors**,
   matching both the existing on-disk JUnit XML and the C5.1 acceptance matrix's own count. Split
   the claim into a current sentence (32/32) and a historical one (the 27-test suite, 10 consecutive
   `stabilityTest` runs), linking the latter to `RELEASE_EVIDENCE.md`.
6. **[P3] This file's own C5.4 write-up claimed prior suite content was "preserved in full"** - the
   diff shows the old fixed-count E2E paragraphs were genuinely replaced (correctly, since they were
   stale), not preserved verbatim. Reworded to "preserved and reorganized... outdated fixed-count E2E
   descriptions were replaced with links to the current acceptance matrix," matching what actually
   happened.

Verified after all six: `./gradlew.bat spotlessCheck` clean, `git diff --check` clean. No suite
re-run needed beyond the fresh `localTest` confirmation above - every other fix was a documentation
correction against already-verified real source. **C5.4 is now closed.**

## C5.5 — Portfolio demo script (2026-09-05)

Wrote [`docs/PORTFOLIO_DEMO.md`](PORTFOLIO_DEMO.md): a fixed nine-step walkthrough (open `/runs` ->
launch `LOCAL`/`JOURNEY` -> watch it live, Progress and Live Focus -> launch `FIXTURE` -> open its
failed test -> show exception/screenshot/trace -> copy the failed step's deep link and open it in a
new tab -> show the `Problems` and `Has artifacts` filters), matching the user's own C5.5 spec
verbatim. Every UI label and route named in it (the launch form's `Environment`/`Suite`
dropdowns and `Run` button, `/runs`/`/runs/:runId`, the `Progress` panel, the "Active now" Live
Focus panel, the Tests table's `Search`/`Status`/`Evidence` filters including the exact `Problems`
and `Has artifacts` option labels, per-row and per-step `Copy link` buttons) was checked against
the real `runner-dashboard` source (`RunLaunchForm.tsx`, `router.tsx`, `TestResultsFilters.tsx`,
`RunDetailsPage.tsx`) before being written into the script, not assumed from memory.

**Live-verified end to end, not just written from source-reading**, against a real
`runner-service` + `npm run dev` dashboard + the already-running local Docker RBP stack (Docker
Desktop was off at the start of this session; started fresh, then `localSutHealth` confirmed all
seven services healthy before proceeding):

- Launched a real `LOCAL`/`JOURNEY` run from the dashboard's own launch form (selecting `LOCAL`
  correctly narrowed Suite to `JOURNEY` alone) and watched `Progress`/"Active now" update live
  while the six journey classes ran against the local stack.
- Launched `PUBLIC`/`FIXTURE` *while the LOCAL run was still active* - **this session's own
  original writeup here was wrong, see the review round below**: the two runs do not progress
  concurrently. `FIXTURE` correctly stayed `QUEUED` until `LOCAL`/`JOURNEY` reached a terminal
  status, then flipped to `RUNNING` on its own. `FIXTURE` reached 2/2 complete (1 passed, 1 failed)
  unattended shortly after that - `CancelDuringStepFixtureTest`'s "blocks mid-step" resolves on its
  own if nothing cancels it, so no manual cancellation step was needed for this script.
- Expanded the failed `StepDrilldownFixtureTest`'s steps, confirmed the real exception text,
  screenshot thumbnail, and working trace download link on its failed step.
- Copied the failed step's deep link and confirmed it in a genuinely separate tab - **found and
  worked around a real tooling gap while doing this**: Chrome does not treat a CDP-dispatched
  click as a trusted user gesture for the Clipboard API, so `navigator.clipboard.writeText` was
  silently rejected on the first attempt (the same reason `DeepLinkE2eTest` itself calls
  `context.grantPermissions(["clipboard-read","clipboard-write"])` before clicking) - worked
  around by capturing the write call's argument directly in-page rather than reading the OS
  clipboard back, then confirming the captured URL genuinely contained `testId=`/`stepId=` and,
  opened in a new tab, auto-revealed and focused the exact failed step. This is a browser-
  automation environment limitation, not a defect in the dashboard's own copy-link feature.
- Applied the `Problems` status filter alone (narrowed 2 tests to 1), cleared it, then applied the
  `Has artifacts` evidence filter alone (also narrowed 2 to 1, the same test) - confirmed
  independently, not just that one filter combination happened to work.

No code was written or modified to make any of this work - the whole script runs on UI and backend
behavior that already existed from prior phases.

### Review round on this section (2026-09-05) - 2 P1 + 2 P2, all fixed and reverified

1. **[P1] Claimed `LOCAL`/`JOURNEY` and `PUBLIC`/`FIXTURE` run concurrently thanks to an
   "environment-scoped lock"** - read `RunService` directly: there is no per-environment lock
   anywhere in it. It holds one single global `ThreadPoolExecutor(1, 1, ..., new
   ArrayBlockingQueue<>(queueCapacity))` - exactly one worker thread, shared by every environment
   and suite. A second run submitted while one is active is queued behind it (`RunStatus.QUEUED`
   is a first-class, fully-modeled state - see `RunStatus.java` and the dashboard's own
   `StatusBadge.tsx`), regardless of environment. The original live session had actually witnessed
   this correctly (`FIXTURE` only appeared to start once `LOCAL`/`JOURNEY` had in fact already
   finished, purely because enough real wall-clock time passed while working around browser-
   automation flakiness) - the "concurrent, environment-scoped lock" explanation was an incorrect
   inference layered on top of an otherwise-real observation, not a fabricated result. **Turned
   into a better, more accurate demo per the reviewer's own suggestion**: re-verified live by
   submitting both runs back-to-back through the real `POST /api/v1/runs` endpoint (`LOCAL`/
   `JOURNEY` runId `83ac354d-...`, then immediately `PUBLIC`/`FIXTURE` runId `84d2907f-...`) -
   confirmed via `GET /api/v1/runs/{id}` polling and screenshotted in the dashboard's own `/runs`
   history table that the second run rendered `QUEUED` (not `RUNNING`) for the entire time the
   first was still `RUNNING`, then automatically flipped to `RUNNING` the instant the first reached
   `SUCCEEDED` - with zero manual intervention. `docs/PORTFOLIO_DEMO.md`'s step 5 now describes and
   asks the presenter to actually show this `QUEUED` -> `RUNNING` transition as the real, correct
   behavior it is, rather than a mistaken concurrency claim.
2. **[P1] This document's own C5.5 writeup repeated the same incorrect "concurrent, no conflict"
   result** - corrected in place above (see the "this session's own original writeup here was
   wrong" note) rather than deleting the history of the mistake, so the record stays honest about
   what was actually claimed first and why it was wrong.
3. **[P2] The demo script skipped one-time setup** (`npm ci`, `playwrightInstall`, JDK 21/Node 24
   prerequisites) and would fail on a fresh machine that hadn't already run the suite before.
   `docs/PORTFOLIO_DEMO.md` gained a "One-time setup" section (linking to the README's own "Run it
   locally in 5 minutes" for full detail) plus a macOS/Linux `./gradlew` note on every command, and
   named Git/Docker Compose as `LOCAL`-only prerequisites.
4. **[P3] "Reproduces the same nine observations every time" overclaimed what a clean `git status`
   alone guarantees** - a healthy local stack, network reachability to the public target, and both
   processes actually running all matter too, and the script itself allows skipping the `LOCAL`
   scenario. Reworded to "reproducible when the documented prerequisites are met," and separated
   deterministic application behavior (which UI states appear, how filters narrow the table, what
   the deep link reveals) from run-to-run-variable values (run IDs, timestamps, exact durations) -
   the latter are now explicitly framed as illustrative examples from one real session, not
   guaranteed numbers.

Verified after all four: re-ran the corrected step 5 live (two real runs via the real REST API,
`QUEUED` -> `RUNNING` transition screenshotted in both the run's own detail page and the `/runs`
history table), `./gradlew.bat spotlessApply` clean, `git diff --check` clean. **C5.5 is now
closed.**

## C5.6 — RC sign-off (2026-09-05)

**Candidate commit: [`a43c88030d9829519cec237d6319d87ec82aed8c`](https://github.com/NemanjaVlaisavljevic/playwright-automation-framework/commit/a43c88030d9829519cec237d6319d87ec82aed8c)**
("Finishing Faze C5.6" - adds `.gitattributes`, see below; the C5.5 work itself is
[`2272ef710f3a670ed2b9718264121de6abdd57b6`](https://github.com/NemanjaVlaisavljevic/playwright-automation-framework/commit/2272ef710f3a670ed2b9718264121de6abdd57b6),
one commit earlier).

### Local gate (this commit's tree, verified fresh - not carried over from an earlier session)

| Check | Result |
|---|---|
| `./gradlew.bat spotlessCheck test :runner-contract:test :runner-listener:test :runner-service:test --rerun` (cold daemon, `PUBLIC`) | ✅ green |
| `npm run check` (`runner-dashboard`: format/lint/boundaries/typecheck/coverage/build) | ✅ green - 97.33%/94.74%/97.2%/97.55% stmt/branch/func/line coverage, unchanged from prior sessions |
| `npm run api:check:snapshot` | ✅ clean |
| `npm run api:check:contract` (against a real, freshly-started `runner-service`) | ✅ clean |
| `./gradlew.bat dashboardE2eTest --rerun` (cold, real backend+dashboard+Chromium) | ✅ 21/21 |
| `./gradlew.bat localSutHealth` then `./gradlew.bat localTest` (fresh local Docker RBP stack) | ✅ 32/32 |
| Orphan processes after the whole gate | ✅ none - only the Gradle daemon remained (itself stopped afterward); confirmed via `Get-CimInstance Win32_Process` that no leftover `runner-service`/`vite`/Chromium process survived |
| `git status`/`git diff --check` | ✅ clean, no stray `build/`/log/artifact files tracked (checked via `git ls-files` against `.log`/`build/`/`node_modules/`/`dist/`/`coverage/`/`allure-results`/`test-results` patterns - the only two hits were legitimately-named source files, `test-results-filter.ts`/`.test.ts`) |
| README/`docs/RELEASE_CANDIDATE.md` render correctly on GitHub | ✅ checked the real rendered pages (not just local preview) - hero image loads, all anchor links and relative doc links (`PORTFOLIO_DEMO.md` included) resolve, no broken-image placeholders or unrendered markdown |

**A real, fixed defect found along the way, not just a clean pass**: `npm run check`'s
`format:check` and both `api:check:snapshot`/`api:check:contract` initially reported ~102 files and
the regenerated OpenAPI client as "dirty," but every one was verified via `git hash-object` (worktree)
vs. `git rev-parse HEAD:<path>` (committed blob) to be **byte-identical to `HEAD`** - a real false
positive, not a real drift. Root cause: this machine's `core.autocrlf=true` checks tracked text files
out as CRLF, which Prettier's default `endOfLine:"lf"` then flags, while `git status` itself also
misreports these files as modified due to the same CRLF/LF mismatch (confirmed via
`git add --renormalize`, which cleared the false "M" flags without changing a single byte of tracked
content). Fixed at the root, not by reformatting-and-hoping: added `.gitattributes`
(`* text=auto eol=lf`, plus explicit `binary` for `jpg`/`jpeg`/`png`/`ico`/`jar`) so every future
checkout on any contributor's machine gets LF regardless of their local `core.autocrlf` - this is the
one code change C5.6 itself introduced, and it is the commit this sign-off targets.

### CI proof - all four workflows green on commit `a43c880`

Each verified against the real GitHub Actions API (`GET .../actions/runs/{id}` and `.../jobs`), not
assumed from a UI screenshot - every job and every step within it (including the failure-only
upload/diagnostic steps, correctly `skipped` since nothing failed) came back `success`:

| Workflow | Run | Conclusion |
|---|---|---|
| `quality-gate.yml` | [33960747887](https://github.com/NemanjaVlaisavljevic/playwright-automation-framework/actions/runs/33960747887) | ✅ success (auto-triggered on push) |
| `dashboard-quality.yml` | [33960849138](https://github.com/NemanjaVlaisavljevic/playwright-automation-framework/actions/runs/33960849138) | ✅ success (both `frontend-quality` and `openapi-contract` jobs) |
| `dashboard-e2e.yml` | [33960912594](https://github.com/NemanjaVlaisavljevic/playwright-automation-framework/actions/runs/33960912594) | ✅ success |
| `local-sut.yml` | [33961215952](https://github.com/NemanjaVlaisavljevic/playwright-automation-framework/actions/runs/33961215952) | ✅ success |

**Not a clean first try, and that's part of the real evidence, not swept under the rug**:
`quality-gate.yml` failed twice in a row on the prior commit (`2272ef7`) before this one - once on
`RoomApiContractTest` (the shared `PUBLIC` target briefly had rooms 4-6 missing `image`/
`description`), once on `BookingAuthorizationApiTest` (the shared target briefly returned `500`
instead of `403` for an anonymous booking read). Both were root-caused against the real, live public
target (not guessed): `git blame`/log confirmed our own suite never creates rooms in a `read-only`
run, and a direct `curl` against `https://automationintesting.online/api/room` and `/api/booking/1`
at investigation time showed clean, schema-valid data and the correct `403` - proving both failures
were transient third-party state on the shared sandbox, not a regression in this repository. Per the
user's own explicit choice, no code was changed to chase these - the job was simply re-run, and it
passed. This is exactly the accepted, documented nature of the `PUBLIC` canary (see the root
README's "Current limitations" and this project's own long-standing classification discipline:
public-target failures are `application`/`infrastructure` noise, not proof the framework itself
regressed) - not a gap C5.6 needed to paper over.

### Portfolio demo

Already proven live end-to-end this same day, in C5.5's own review round (see above) - all nine
script steps walked through for real against a real `runner-service` + dashboard + local Docker RBP
stack, including the corrected `QUEUED` -> `RUNNING` step 5. Not independently re-run a third time
for C5.6 itself; re-verifying the exact same script a second time in the same session would have
added nothing beyond what C5.5's own review round already proved.

### Known limitations (carried into the RC, not hidden)

Unchanged from the root README's own "Current limitations" section, all reconfirmed accurate at
this commit: in-memory/on-local-disk-only run history and event journal (no restart recovery), a
single-instance runner with one global single-worker run queue (see C5.5's own corrected finding -
there is no per-environment concurrency), no authentication/authorization, no artifact retention
policy, and no deployment packaging (no Dockerfile, no same-origin production serving). Each is an
explicit RC-stage scope decision this whole C5 gate exists to hand off cleanly to Phase D, not a
defect discovered late.

### Versioning decision

Per the user's own explicit choice between the two options C5.6 was scoped to decide between:
**close C5.6 as a portfolio RC without a real semantic-version tag.** The Java modules are still
`1.0.0-SNAPSHOT` and `runner-dashboard/package.json` is still `0.0.0` - a real `v1.0.0-rc.1` tag
would overclaim readiness before any of that is actually reconciled, and before a deployable package
(Phase D) exists to be a release candidate *of*. Versioning gets decided and aligned once Phase D's
packaging work is underway, not now.

### Final decision

**GO for portfolio release candidate**, at commit `a43c88030d9829519cec237d6319d87ec82aed8c`. All
automated tests green, all four CI workflows green on this exact commit, local gate green including
a fresh `dashboardE2eTest`/`localTest` run, no accidentally-tracked build/log/artifact files, `git
diff --check` clean, README/docs render correctly on GitHub, OpenAPI snapshot fresh, and the
portfolio demo script proven live end-to-end. **C5 is now fully closed.** Next: Faza D, starting
with a D0 deployment-architecture spike before any Dockerfile is written.

## Post-RC — Faza D0.5: Custom PUBLIC runs (2026-09-05)

**C5.6's sign-off above is unchanged and remains the locked, accurate RC baseline** at commit
`a43c88030d9829519cec237d6319d87ec82aed8c` - nothing in this section retroactively edits or
re-litigates that gate. D0.5 is a new functional increment layered on top of it, built after the
RC closed and before Faza D1 (production packaging): it lets a dashboard user hand-pick individual
`PUBLIC`, read-only tests from a server-generated catalog and launch exactly those as a new
`Suite.CUSTOM`, without the client ever being able to send a Gradle task name, tag, class, method,
or arbitrary `--tests` expression - only a `testKey` chosen from, and validated against, a
server-side catalog the server itself generates and re-validates.

### What was built

- `TestCatalogGenerator` (main suite `tooling` package): JUnit Platform discovery-only generator
  producing the committed `src/test/resources/catalog/public-test-catalog.json` allowlist -
  fail-fast on a non-unique/non-canonical `testKey`, a missing `read-only` tag, a present
  `mutation`/`fixture` tag, or anything but exactly one `api`/`ui`/`journey` layer tag; tags sorted
  via `TreeSet` so the committed snapshot never drifts on `Set`-iteration-order alone.
- `testCatalogGenerate`/`testCatalogCheck` Gradle tasks (root `build.gradle`), the latter wired into
  `quality-gate.yml` as a CI drift gate on every push/PR, mirroring the existing OpenAPI snapshot
  guard.
- `runner-service`: `TestCatalogService` (re-reads and re-validates the catalog file on every call
  via `TestCatalogContentValidator` - the same uniqueness/canonical/exactly-one-layer/tag checks
  applied again at runtime-load time, since a deployed catalog file is untrusted input, not an
  inherently-safe artifact), `GET /api/v1/tests?environment=PUBLIC`, `CustomTestSelectionValidator`
  (the one place a client-submitted `testKey` list becomes a trustworthy, immutable
  `SelectedTestSnapshot` list), a new `customTest` Gradle task, and `Suite.CUSTOM` end to end through
  `RunService`/`SuiteCommandFactory`/the REST contract.
- Dashboard: `CustomTestPicker` (search, Layer dropdown, separate Smoke-only checkbox, select-all-
  visible, clear-selection), wired into `RunLaunchForm` behind the existing `Suite` dropdown.

### Review round on this increment - 4 P1 + 3 P2 + 1 P3 fixed, 1 P3 deliberately deferred

1. **[P1] `testCatalogCheck` wasn't wired into any CI workflow** - fixed: added as a step in
   `quality-gate.yml` immediately after the existing read-only test gate.
2. **[P1] `testKey` uniqueness/canonical-form/exactly-one-layer/tag-sorting not enforced by the
   generator** - fixed in `TestCatalogGenerator` (see above); re-generated the real catalog and
   confirmed byte-identical content to the pre-fix version, now provably validated rather than
   incidentally correct.
3. **[P1] `TestCatalogService` performed no content validation at runtime-load time** - fixed: added
   `TestCatalogContentValidator`, applying the same checks the generator enforces, invoked on every
   `current()` call; also fixed `CustomTestSelectionValidator`'s catalog-to-map step, which
   previously overwrote a duplicate `testKey` silently (last one wins) - it now fails fast instead.
4. **[P1] No permanent, committed E2E scenario for `Suite.CUSTOM`** (only a manual, in-session
   browser check existed) - fixed: added `CustomRunE2eTest` to the `dashboardE2eTest` source set -
   selects exactly two stable `PUBLIC` tests through the real `CustomTestPicker`, launches `CUSTOM`,
   confirms `SUCCEEDED`/`Total=2`/`Passed=2`, confirms both selected tests' rows are present and a
   third, unselected catalog test's row is absent, and confirms the REST response's own
   `selectedTests` snapshot matches the selection exactly via a direct same-origin `GET`. **Run live
   against the real backend + dashboard + Chromium and passed** (not just written from source-
   reading) before being considered done.
5. **[P2] `CustomTestPicker`'s catalog query had no retry/recovery pattern** - fixed: mirrors the
   existing capabilities/health-query pattern (poll while erroring + `refetchIntervalInBackground:
   true`, with a testable interval prop). Two new tests prove recovery without remounting, including
   a backgrounded-tab scenario - the latter was verified to actually fail without
   `refetchIntervalInBackground: true` before being trusted as a meaningful regression test, not
   just written and assumed correct.
6. **[P2] `TestCatalogUnavailableException`'s client-facing message embedded a resolved absolute
   filesystem path**, leaked verbatim by `RunExceptionHandler` in a 503 `ProblemDetail.detail` -
   fixed to match the existing `ArtifactManifestCorruptException` pattern: a generic client-facing
   message, with the real path carried only in a `diagnosticReason()` logged server-side. A logback
   `ListAppender` test proves the path reaches the log but never the client-facing response.
7. **[P2] No full-chain `RunServiceTest` for `CUSTOM`** - fixed: added a test exercising catalog load
   -> validation -> immutable selection snapshot -> the actual launched `customTest --tests ...`
   command (with a second, unselected catalog entry proving the command carries exactly the selected
   filter and nothing else), plus a negative-path test proving an invalid selection never saves a
   `Run`, never emits any event, and never launches a process. A `RunControllerTest` addition proves
   the request body's `testKeys` reach `RunService.submit` and the response's `selectedTests`
   reflects what the service returned.
8. **[P3] `RunResponse` exposed the domain `SelectedTestSnapshot` record directly** - fixed: added a
   dedicated `SelectedTestResponse` DTO with explicit mapping, mirroring `RunResponse`'s own existing
   separation from the `Run` domain record. Re-exported the OpenAPI spec from a real running backend
   and regenerated the TypeScript client (the schema component name changed from
   `SelectedTestSnapshot` to `SelectedTestResponse`, field shape unchanged) - full frontend `npm run
   check` and the backend test suite both re-verified green afterward.
9. **[P3, deliberately deferred] `maxSelectableTests` (currently 25, enforced server-side) is not
   exposed to the frontend** - `CustomTestPicker`'s "Select all visible" could exceed the limit once
   the catalog grows past 25 entries. Left deferred, per the explicit condition it was accepted
   under: the real catalog has 10 entries today, well under the cap, and server-side validation
   (`CustomTestSelectionValidator`) remains the final authority regardless - an over-limit request is
   still rejected with a 400, never silently truncated or accepted. Must be revisited before the
   catalog is allowed to grow past 25, and preferably before D2.

### Known limitations (D0.5-specific, in addition to the RC's own)

- The `maxSelectableTests` cap (25) is invisible to the frontend, as described above (P3,
  deliberately deferred).
- `RunResponse.selectedTests` is now its own `SelectedTestResponse` DTO, but the underlying domain
  `SelectedTestSnapshot`/`Run.selectedTests()` shape is still what D2's planned
  `run_selected_tests` Postgres table is designed to persist - no persistence exists yet; a `CUSTOM`
  run's selection lives only in the same in-memory `Run` record as everything else the RC's own
  "Known limitations" section already discloses (no restart recovery).
- No authentication/authorization gates catalog listing or `CUSTOM` submission specifically - covered
  by the RC's existing, broader "no authentication/authorization" limitation, not a new gap.

Verified after all fixes: `./gradlew.bat spotlessCheck test :runner-service:test` green, `npm run
check` (frontend format/lint/boundaries/typecheck/coverage/build) green, `dashboardE2eTest`'s
`CustomRunE2eTest` and `RunLifecycleE2eTest` both re-run live and green after the `SelectedTestResponse`
DTO change, and the OpenAPI snapshot/generated client re-exported and regenerated from a real running
backend rather than hand-edited.
