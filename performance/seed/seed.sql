-- D4.4.1a - deterministic performance-baseline seed. Run ONLY via performance-seed's own
-- fail-closed seed.sh wrapper (never directly) - that wrapper refuses to proceed unless
-- current_database() = 'runner_performance', a dedicated, fully ephemeral database that exists
-- only for the isolated `runner-performance` Compose project (see
-- deploy/docker-compose.performance.yml) - never the real portfolio deployment's own `runner`
-- database. Expects a psql variable `real_size` (the real, measured byte count of the artifact
-- fixture seed.sh already copied onto disk before this script ever runs).
--
-- Idempotent/re-runnable (a review finding): every seed-owned row is removed and re-inserted in
-- the same transaction, never a global TRUNCATE (which would also erase anything a concurrent/
-- non-seed process wrote) - relying on each child table's own ON DELETE CASCADE from `runs`, so
-- one DELETE against `runs` alone is sufficient. Structurally deterministic across runs (same row
-- counts, same suite/status distribution, same relationships, same artifact byte size) - not
-- byte-identical, since every timestamp derives from this transaction's own `now()`.
--
-- Exactly 500 terminal runs total (never 500-plus-extras): 480 plain runs across five ordinary
-- suites, 19 CUSTOM-suite runs (each with a handful of run_selected_tests rows), and one
-- perf-replay-run with ~399 run_events (for the SSE replay/time-to-first-event scenario). A
-- separate 501st row, perf-hold-open-run (RUNNING, for the SSE connection-cap scenario), is
-- deliberately NOT seeded here - see D4.4.1c's own seeding step, which must run only after this
-- application's own startup readiness/recovery pass has already completed, or D2.5's own
-- crash-recovery would reclassify a directly-inserted RUNNING row with no real backing process as
-- ERROR.
--
-- Every date is within the last 13 days, so D4.1's own retention sweep (its default
-- runHistoryMaxAge/artifactMaxAge windows) can never purge or reclassify a seeded row mid-
-- measurement.

BEGIN;

-- to_char below formats in whatever timezone the session is in - force UTC so every emitted
-- timestamp literal ends in a real "Z", matching what Jackson's Instant deserializer expects.
SET LOCAL TIME ZONE 'UTC';

-- Idempotent re-seed: remove only this seed's own rows before re-inserting. Every seeded id is
-- `perf-`-prefixed by construction (see every INSERT below), so this single pattern covers all of
-- them; run_selected_tests/run_events/artifacts all cascade from this DELETE via their own
-- fk_..._run ON DELETE CASCADE constraints (see db/migration/V1__create_runner_schema.sql).
DELETE FROM runs WHERE run_id LIKE 'perf-%';

-- 480 plain terminal runs, varied suite/status, realistic-shaped timestamps. Suite/status choice
-- is a deterministic function of i (not random()) so a re-run of this script - e.g. between the
-- 3-5 D4.4.2 measurement passes - produces the same structure every time.
INSERT INTO runs (
    run_id, environment, suite, status, requested_at, started_at, finished_at, exit_code, detail,
    next_event_sequence, version
)
SELECT
    'perf-run-' || lpad(i::text, 4, '0'),
    'PUBLIC',
    suite,
    status,
    requested_at,
    requested_at + interval '5 seconds',
    requested_at + interval '5 seconds' + duration,
    CASE status WHEN 'SUCCEEDED' THEN 0 WHEN 'FAILED' THEN 1 ELSE NULL END,
    CASE status
        WHEN 'FAILED' THEN 'Gradle exited with code 1'
        WHEN 'TIMED_OUT' THEN 'Process exceeded the configured timeout and was killed'
        WHEN 'CANCELLED' THEN 'Run was cancelled'
        WHEN 'ERROR' THEN 'Unexpected failure: simulated seed data'
        ELSE NULL
    END,
    1,
    0
FROM (
    SELECT
        i,
        (ARRAY['SMOKE', 'API', 'UI', 'JOURNEY', 'REGRESSION'])[1 + ((i * 7) % 5)] AS suite,
        (ARRAY['SUCCEEDED', 'SUCCEEDED', 'SUCCEEDED', 'SUCCEEDED', 'SUCCEEDED', 'SUCCEEDED',
               'FAILED', 'CANCELLED', 'TIMED_OUT', 'ERROR'])[1 + ((i * 13) % 10)] AS status,
        now() - ((i % 13) || ' days')::interval - ((i % 24) || ' hours')::interval AS requested_at,
        ((30 + (i % 20) * 60) || ' seconds')::interval AS duration
    FROM generate_series(1, 480) AS i
) AS base;

-- 19 CUSTOM-suite runs, each with a handful of selected tests (run_selected_tests).
INSERT INTO runs (
    run_id, environment, suite, status, requested_at, started_at, finished_at, exit_code,
    next_event_sequence, version
)
SELECT
    'perf-run-custom-' || lpad(i::text, 3, '0'),
    'PUBLIC',
    'CUSTOM',
    'SUCCEEDED',
    requested_at,
    requested_at + interval '5 seconds',
    requested_at + interval '95 seconds',
    0,
    1,
    0
FROM (
    SELECT i, now() - ((i % 13) || ' days')::interval AS requested_at
    FROM generate_series(1, 19) AS i
) AS base;

INSERT INTO run_selected_tests (run_id, ordinal, test_key, display_name, layer)
SELECT
    'perf-run-custom-' || lpad(run_i::text, 3, '0'),
    test_i,
    'dev.vlaisanem.automation.tests.api.PerfSeedTest#test' || test_i,
    'Performance seed test ' || test_i,
    'API'
FROM generate_series(1, 19) AS run_i, generate_series(0, 2) AS test_i;

-- perf-replay-run: the 500th terminal run, ~399 run_events for SSE replay/time-to-first-event
-- measurement (D4.4.1c).
INSERT INTO runs (
    run_id, environment, suite, status, requested_at, started_at, finished_at, exit_code,
    next_event_sequence, version
)
VALUES (
    'perf-replay-run', 'PUBLIC', 'REGRESSION', 'SUCCEEDED',
    now() - interval '1 day',
    now() - interval '1 day' + interval '5 seconds',
    now() - interval '1 day' + interval '15 minutes',
    0, 400, 0
);

-- sequence 1: RUN_QUEUED (at base), sequence 2: RUN_STARTED (base + 5s). Every subsequent event
-- below is timestamped strictly at or after RUN_STARTED's own occurred_at - a review finding: the
-- first draft had early TEST_STARTED events timestamped *before* RUN_STARTED, a timeline the real
-- system could never produce, which the acceptance check at the end of this script now guards
-- against permanently.
INSERT INTO run_events (run_id, sequence, event_type, occurred_at, payload)
VALUES
    (
        'perf-replay-run', 1, 'RUN_QUEUED', now() - interval '1 day',
        jsonb_build_object(
            'schemaVersion', '1.1', 'runId', 'perf-replay-run', 'sequence', 1,
            'timestamp', to_char(now() - interval '1 day', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
            'type', 'RUN_QUEUED'
        )
    ),
    (
        'perf-replay-run', 2, 'RUN_STARTED', now() - interval '1 day' + interval '5 seconds',
        jsonb_build_object(
            'schemaVersion', '1.1', 'runId', 'perf-replay-run', 'sequence', 2,
            'timestamp',
            to_char(now() - interval '1 day' + interval '5 seconds', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
            'type', 'RUN_STARTED'
        )
    );

-- 198 TEST_STARTED/TEST_PASSED pairs = 396 test-level events, sequences 3..398. TEST_STARTED(t) is
-- at RUN_STARTED + t seconds, TEST_PASSED(t) one second later - each pair strictly at or after the
-- previous one (monotonically non-decreasing, never regressing), and every one at or after
-- RUN_STARTED itself.
INSERT INTO run_events (run_id, sequence, event_type, occurred_at, payload)
SELECT
    'perf-replay-run',
    3 + (t - 1) * 2,
    'TEST_STARTED',
    now() - interval '1 day' + interval '5 seconds' + (t || ' seconds')::interval,
    jsonb_build_object(
        'schemaVersion', '1.1', 'runId', 'perf-replay-run', 'sequence', 3 + (t - 1) * 2,
        'timestamp',
        to_char(
            now() - interval '1 day' + interval '5 seconds' + (t || ' seconds')::interval,
            'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'
        ),
        'type', 'TEST_STARTED', 'testId', 'perf-test-' || t,
        'testDisplayName', 'Performance seed test ' || t
    )
FROM generate_series(1, 198) AS t
UNION ALL
SELECT
    'perf-replay-run',
    4 + (t - 1) * 2,
    'TEST_PASSED',
    now() - interval '1 day' + interval '5 seconds' + (t || ' seconds')::interval + interval '1 second',
    jsonb_build_object(
        'schemaVersion', '1.1', 'runId', 'perf-replay-run', 'sequence', 4 + (t - 1) * 2,
        'timestamp',
        to_char(
            now() - interval '1 day' + interval '5 seconds' + (t || ' seconds')::interval
                + interval '1 second',
            'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'
        ),
        'type', 'TEST_PASSED', 'testId', 'perf-test-' || t,
        'testDisplayName', 'Performance seed test ' || t
    )
FROM generate_series(1, 198) AS t;

-- Final RUN_FINISHED, sequence 399, at base + 15 minutes - well after the last test event
-- (base + 5s + 198s + 1s at most).
INSERT INTO run_events (run_id, sequence, event_type, occurred_at, payload)
VALUES (
    'perf-replay-run', 399, 'RUN_FINISHED', now() - interval '1 day' + interval '15 minutes',
    jsonb_build_object(
        'schemaVersion', '1.1', 'runId', 'perf-replay-run', 'sequence', 399,
        'timestamp',
        to_char(now() - interval '1 day' + interval '15 minutes', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
        'type', 'RUN_FINISHED', 'runOutcome', 'SUCCEEDED'
    )
);

-- 5 artifact rows (one SCREENSHOT each) on the first five plain runs, paired with the real fixture
-- PNG seed.sh already copied onto the mounted artifacts volume before this transaction ever
-- started - size_bytes is the psql variable seed.sh measured from that real file (`:real_size`),
-- never a guessed/hardcoded constant (see ArtifactFileResolver's own real-path-plus-size
-- expectations).
INSERT INTO artifacts (
    artifact_id, run_id, test_id, test_display_name, schema_version, artifact_type, relative_path,
    size_bytes, media_type, created_at
)
SELECT
    'perf-artifact-' || lpad(i::text, 4, '0'),
    'perf-run-' || lpad(i::text, 4, '0'),
    'perf-test-artifact-' || i,
    'Performance seed artifact test ' || i,
    '1.1',
    'SCREENSHOT',
    'screenshot-1.png',
    :real_size,
    'image/png',
    now() - ((i % 13) || ' days')::interval
FROM generate_series(1, 5) AS i;

-- Runtime acceptance check (a review finding): perf-replay-run's own event timeline must never
-- regress in time as sequence increases - a real load-test replay must never measure a journal the
-- real system could never have produced. Fails the whole seed transaction, not just a one-off
-- manual check, if this is ever violated again.
DO $$
DECLARE
    regressions integer;
BEGIN
    SELECT count(*) INTO regressions
    FROM (
        SELECT occurred_at, lag(occurred_at) OVER (ORDER BY sequence) AS prev_occurred_at
        FROM run_events
        WHERE run_id = 'perf-replay-run'
    ) AS ordered
    WHERE prev_occurred_at IS NOT NULL AND occurred_at < prev_occurred_at;

    IF regressions > 0 THEN
        RAISE EXCEPTION
            'performance-seed: % run_events row(s) for perf-replay-run have occurred_at going backward relative to sequence order',
            regressions;
    END IF;
END $$;

COMMIT;
