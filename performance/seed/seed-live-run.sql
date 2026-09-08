-- D4.4.1c - inserts perf-hold-open-run (RUNNING), used by sse.js for the 3-concurrent-connections
-- + 4th-rejected scenario. MUST run only after runner-service's own startup readiness/recovery
-- pass has already completed - deploy/docker-compose.performance.yml gates the
-- `performance-seed-live` service on `runner-service: condition: service_healthy`, and
-- `/actuator/health/readiness` only ever reports UP once RunRecoveryService's one-time startup
-- pass has finished (the `recovery` readiness-group member reports OUT_OF_SERVICE until then), so
-- this ordering is structurally guaranteed, not just hoped for. Inserting this exact row *before*
-- the application ever started would have D2.5's own crash-recovery pass immediately reclassify
-- it as ERROR (no real backing process).
--
-- Idempotent: deletes any existing perf-hold-open-run first (never a global TRUNCATE) - safe to
-- re-run before another SSE measurement pass.
--
-- Review finding - a real RUNNING run always already has its own RUN_QUEUED/RUN_STARTED events and
-- next_event_sequence = 3 by the time anything can observe it as RUNNING (RunLifecycleCoordinator
-- never flips status without also recording those two events first) - an empty-history fixture
-- with next_event_sequence = 1 is a state the real system can never produce. This was not just a
-- realism gap: it was the actual root cause of the SSE connection-cap scenario's own header-flush
-- timing bug (see performance/k6/sse-connection-cap.js's own comment) - a real RUNNING run's SSE
-- subscription replays its two buffered events immediately on connect, flushing real response
-- bytes right away, whereas an empty-history fixture sends nothing until the next periodic
-- heartbeat (15s by default). Seeding the same two canonical events here fixes both problems at
-- once: replay now happens immediately (like a real run), and the connection still stays open
-- afterward (RUNNING never completes) for the connection-cap scenario to hold.

BEGIN;
SET LOCAL TIME ZONE 'UTC';

DELETE FROM runs WHERE run_id = 'perf-hold-open-run';

INSERT INTO runs (
    run_id, environment, suite, status, requested_at, started_at, next_event_sequence, version
)
VALUES (
    'perf-hold-open-run', 'PUBLIC', 'REGRESSION', 'RUNNING', now(), now(), 3, 0
);

INSERT INTO run_events (run_id, sequence, event_type, occurred_at, payload)
VALUES
    (
        'perf-hold-open-run', 1, 'RUN_QUEUED', now(),
        jsonb_build_object(
            'schemaVersion', '1.1', 'runId', 'perf-hold-open-run', 'sequence', 1,
            'timestamp', to_char(now(), 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'), 'type', 'RUN_QUEUED'
        )
    ),
    (
        'perf-hold-open-run', 2, 'RUN_STARTED', now(),
        jsonb_build_object(
            'schemaVersion', '1.1', 'runId', 'perf-hold-open-run', 'sequence', 2,
            'timestamp', to_char(now(), 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'), 'type', 'RUN_STARTED'
        )
    );

COMMIT;
