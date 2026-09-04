# Release Candidate — C5

C5 adds no new functionality. It proves that everything already built works together,
reproducibly, from a clean environment, with documentation a stranger could follow. This file is
the living record of that proof, filled in section by section as each C5 sub-phase closes:

- **C5.1 — Acceptance matrix** (this file, below): does real evidence already exist for every
  scenario an RC needs, or is there a real gap?
- **C5.2 — Clean-environment reproducibility** (this file, below): done.
- **C5.3 — Three CI proofs on one commit** (this file, below): done.
- **C5.4 — Portfolio README**: done - see the root [`README.md`](../README.md).
- **C5.5 — Portfolio demo script**: not yet started.
- **C5.6 — RC sign-off**: not yet started.

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

The prior suite-focused content (quick start, local Docker target, dashboard E2E test docs,
configuration table, project structure, executable-coverage table, known application issues, build
tooling backlog) was preserved in full, not deleted - relocated under a new "Automation suite"
section beneath the portfolio-facing material, since it's still accurate and valuable, just no
longer the document's own headline.

Verified: `./gradlew.bat spotlessCheck` clean (caught and fixed one real formatting violation on the
first pass - `spotlessMisc` also lints Markdown, not just source, and flagged this file; a plain
`spotlessApply` fixed it with no content change), `git diff --check` clean, and every internal link/
anchor checked against the doc's own real heading text (`#quick-start-suite-only-no-dashboard` for
this file's own relocated section, `#architecture-current` for `runner-dashboard/README.md`'s
existing heading) rather than assumed. **C5.4 is now closed.**
