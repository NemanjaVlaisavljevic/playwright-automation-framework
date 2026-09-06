-- D2.1 (Faza D2 - see docs/DEPLOYMENT_ARCHITECTURE.md section 3): the four tables that eventually
-- replace RunRepository's in-memory ConcurrentHashMap and FileBackedRunEventJournal's per-run
-- JSONL files as the canonical, durable store. Not wired into the live application yet (see
-- RunnerServiceApplication's own Javadoc) - this migration exists so databaseIntegrationTest can
-- prove the schema is correct against a real Postgres before any repository code depends on it.
-- Not yet released to any real deployment - reviewed once already (2026-09-06) and corrected
-- directly in this same V1 file rather than a follow-up V2, which is only appropriate before the
-- first real deployment ever applies it. Any future schema change, once this has shipped for real,
-- must be a new versioned migration instead.
--
-- Column/constraint values are deliberately not tied to today's exact Java enum literals
-- (Environment/Suite/EventType) via a CHECK ... IN (...) list, except where the value set is
-- itself part of a locked, small design (status/layer/artifact_type) - environment/suite/
-- event_type are all expected to grow new values over time (a new Suite, a future STEP_*
-- addition), and a CHECK constraint enumerating today's exact set would then require a migration
-- on every such addition purely to keep the constraint in sync - the Java layer already owns that
-- validation (RunStateMachine, CustomTestSelectionValidator, the *_ContentValidator classes)
-- before a row is ever written.
CREATE TABLE runs (
    run_id              VARCHAR(64)  NOT NULL,
    environment         VARCHAR(32)  NOT NULL,
    suite                VARCHAR(32) NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    requested_at        TIMESTAMPTZ  NOT NULL,
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    exit_code           INTEGER,
    detail              TEXT,
    process_log_path    TEXT,
    next_event_sequence BIGINT       NOT NULL DEFAULT 1,
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_runs PRIMARY KEY (run_id),
    -- Mirrors RunStatus.java exactly. This one IS enumerated: RunStatus's own value set is the
    -- locked state-machine design (RunStateMachine), not an open-ended growth point the way
    -- environment/suite/event_type are - a new status is a state-machine design change, not a
    -- routine addition, and should require a deliberate migration either way.
    CONSTRAINT chk_runs_status CHECK (
        status IN ('QUEUED', 'STARTING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'ERROR')
    ),
    CONSTRAINT chk_runs_next_event_sequence CHECK (next_event_sequence > 0),
    CONSTRAINT chk_runs_version CHECK (version >= 0),
    -- The next five constraints mirror Run.java's own compact-constructor invariants exactly, so a
    -- row this schema accepts can always be hydrated back into a valid Run - reviewed finding:
    -- without these, Postgres would happily store e.g. RUNNING with no started_at, or a terminal
    -- run with no finished_at, that Run's own constructor then rejects on read.
    --
    -- startedAt: required once RUNNING/SUCCEEDED/FAILED/TIMED_OUT; forbidden while QUEUED/STARTING;
    -- optional (either) for CANCELLED/ERROR, which can occur before or after the process launched.
    CONSTRAINT chk_runs_started_at_by_status CHECK (
        (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT') AND started_at IS NOT NULL)
        OR (status IN ('QUEUED', 'STARTING') AND started_at IS NULL)
        OR (status IN ('CANCELLED', 'ERROR'))
    ),
    -- finishedAt: required exactly when the status is terminal, forbidden otherwise.
    CONSTRAINT chk_runs_finished_at_by_status CHECK (
        (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'ERROR') AND finished_at IS NOT NULL)
        OR (status IN ('QUEUED', 'STARTING', 'RUNNING') AND finished_at IS NULL)
    ),
    -- exitCode/detail ("a result") only ever accompany a terminal status.
    CONSTRAINT chk_runs_result_only_when_terminal CHECK (
        status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'ERROR')
        OR (exit_code IS NULL AND detail IS NULL)
    ),
    -- Chronological ordering - matches Run's own isBefore checks (equal timestamps are allowed).
    CONSTRAINT chk_runs_started_at_after_requested CHECK (started_at IS NULL OR started_at >= requested_at),
    CONSTRAINT chk_runs_finished_at_after_requested CHECK (finished_at IS NULL OR finished_at >= requested_at),
    CONSTRAINT chk_runs_finished_at_after_started CHECK (
        finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at
    )
);

-- Backs RunRepository.findAll's existing sort (requestedAt descending).
CREATE INDEX idx_runs_requested_at ON runs (requested_at DESC);

-- The fourth table - without it, a CUSTOM run's List<SelectedTestSnapshot> (Faza D0.5) would not
-- survive a restart, even though Run's own compact constructor was written specifically to make
-- that snapshot immutable through the run's whole lifecycle. One row per selected test; `ordinal`
-- preserves the original selection order, which is not otherwise re-derivable from any column here.
CREATE TABLE run_selected_tests (
    run_id       VARCHAR(64) NOT NULL,
    ordinal      INTEGER     NOT NULL,
    test_key     TEXT        NOT NULL,
    display_name TEXT        NOT NULL,
    -- Mirrors TestLayer.java exactly (API/UI/JOURNEY) - see the runs.status comment above for why
    -- this one is enumerated while environment/suite/event_type are not: TestLayer is the main
    -- automation suite's own fixed taxonomy (AutomationExtension requires exactly one per test),
    -- not an open-ended set expected to grow the way Suite is.
    layer        VARCHAR(16) NOT NULL,
    CONSTRAINT pk_run_selected_tests PRIMARY KEY (run_id, ordinal),
    CONSTRAINT fk_run_selected_tests_run FOREIGN KEY (run_id) REFERENCES runs (run_id) ON DELETE CASCADE,
    -- Mirrors SelectedTestSnapshot's own no-duplicate-testKey invariant (enforced in Run's compact
    -- constructor) at the database layer too, not only in Java.
    CONSTRAINT uq_run_selected_tests_test_key UNIQUE (run_id, test_key),
    CONSTRAINT chk_run_selected_tests_ordinal CHECK (ordinal >= 0),
    CONSTRAINT chk_run_selected_tests_layer CHECK (layer IN ('API', 'UI', 'JOURNEY'))
);

-- The canonical, sequence-numbered event journal - replaces FileBackedRunEventJournal/RunEventHub's
-- file-backed replay source. The raw per-run JSONL the JUnit listener/Steps API write is untouched
-- by this table - original input and debug evidence, never replaced.
CREATE TABLE run_events (
    run_id      VARCHAR(64)  NOT NULL,
    sequence    BIGINT       NOT NULL,
    event_type  VARCHAR(32)  NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL,
    payload     JSONB        NOT NULL,
    CONSTRAINT pk_run_events PRIMARY KEY (run_id, sequence),
    CONSTRAINT fk_run_events_run FOREIGN KEY (run_id) REFERENCES runs (run_id) ON DELETE CASCADE,
    CONSTRAINT chk_run_events_sequence CHECK (sequence > 0)
);

-- Metadata only - matches ArtifactManifestEntry (runner-contract) field for field, not a lossy
-- subset of it: reviewed finding - test_id/test_display_name were originally nullable/missing here,
-- which would have made this table unable to reconstruct the same ArtifactSummaryResponse shape
-- (both fields are NOT NULL on the wire contract) after a restart without an extra, less reliable
-- join back through run_events. step_id is the one field that stays genuinely optional, mirroring
-- ArtifactManifestEntry.stepId's own nullability (a test that never used the Steps API). The actual
-- screenshot/trace/video/log files stay on the persistent volume (/data/runner-artifacts/...),
-- never inlined here. Ingested incrementally (TEST_FAILED/TEST_ABORTED, plus a final
-- pre-RUN_FINISHED drain), not only once at the end - see docs/DEPLOYMENT_ARCHITECTURE.md's
-- "Artifacts must ingest incrementally" section for why a bulk end-of-run import would regress an
-- already-proven dashboard feature.
CREATE TABLE artifacts (
    -- ArtifactManifestEntry's own artifactId pattern is [A-Za-z0-9][A-Za-z0-9._~-]{0,127} (max 128
    -- characters) - sized to match exactly, not the 64 characters other id columns use here.
    artifact_id      VARCHAR(128) NOT NULL,
    run_id           VARCHAR(64)  NOT NULL,
    test_id          TEXT         NOT NULL,
    test_display_name TEXT        NOT NULL,
    step_id          TEXT,
    -- Mirrors ArtifactManifestEntry.schemaVersion - the manifest's own wire contract is already
    -- versioned (ArtifactManifestEntry.CURRENT_SCHEMA_VERSION), so the row that ingested it should
    -- record which version it was ingested under too.
    schema_version   VARCHAR(16)  NOT NULL,
    -- Mirrors ArtifactType.java exactly (SCREENSHOT/TRACE/VIDEO) - see the runs.status comment
    -- above for why this one IS enumerated: unlike Suite (which grows with routine new suites),
    -- ArtifactType is a small, deliberately-closed set the runner-contract module itself owns.
    artifact_type    VARCHAR(32)  NOT NULL,
    relative_path    TEXT         NOT NULL,
    size_bytes       BIGINT       NOT NULL,
    media_type       VARCHAR(128) NOT NULL,
    -- No DEFAULT now() here, deliberately - unlike runs.created_at/updated_at (this service's own
    -- bookkeeping timestamps), this column represents ArtifactManifestEntry.createdAt, the
    -- artifact file's own original creation time as recorded by the writer. Ingestion must persist
    -- that original value explicitly; defaulting to "whenever this row happened to be inserted"
    -- would silently substitute a different, less meaningful timestamp.
    created_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_artifacts PRIMARY KEY (artifact_id),
    CONSTRAINT fk_artifacts_run FOREIGN KEY (run_id) REFERENCES runs (run_id) ON DELETE CASCADE,
    CONSTRAINT chk_artifacts_type CHECK (artifact_type IN ('SCREENSHOT', 'TRACE', 'VIDEO')),
    CONSTRAINT chk_artifacts_size_bytes CHECK (size_bytes >= 0)
);

-- Backs the dashboard's own group-by-(testId, stepId) artifact grouping (Faza B).
CREATE INDEX idx_artifacts_run_test_step ON artifacts (run_id, test_id, step_id);
