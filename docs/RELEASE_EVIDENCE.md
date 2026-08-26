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
