-- Faza D4.1 (docs/RELEASE_EVIDENCE.md's D4.1 section) - retention bookkeeping, deliberately three
-- plain nullable columns rather than a new RunStatus value: cleanup/artifact-purge is orthogonal to
-- a run's own domain lifecycle (a SUCCEEDED run stays SUCCEEDED forever - these columns record
-- retention state about it, never a state the RunStateMachine itself transitions through).
--
-- cleanup_started_at: set once, atomically (RetentionService#claimForCleanup), the moment a run is
-- selected for full deletion - RunLifecycleStore#findById/findAll are filtered to exclude any row
-- with this set, so the run disappears from every public read path (including SSE replay, which
-- goes through the same findById-backed lookup) immediately, well before its files/row are actually
-- gone. Only RetentionService's own internal queries (findEligibleForCleanup/findPendingCleanup)
-- ever see a tombstoned row - this is what lets a crash mid-cleanup be safely resumed without ever
-- letting a user briefly see a run whose files are being deleted out from under it.
--
-- artifacts_purge_started_at / artifacts_purged_at: the separate, shorter-window, per-run artifact
-- purge protocol (independent of full-run cleanup - a run's own history can outlive its artifact
-- files). _started_at is the atomic claim; _purged_at is only ever set together with deleting every
-- artifacts row for that run, in the same transaction (ArtifactRepository#completePurge) - a reader
-- can never observe one without the other.
ALTER TABLE runs
    ADD COLUMN cleanup_started_at TIMESTAMPTZ,
    ADD COLUMN artifacts_purge_started_at TIMESTAMPTZ,
    ADD COLUMN artifacts_purged_at TIMESTAMPTZ;

-- The actual safety property this whole feature depends on, made a database invariant rather than
-- just something application code is trusted to get right: a non-terminal run can never be marked
-- for cleanup or artifact purge, full stop, regardless of any bug in RetentionService itself.
ALTER TABLE runs ADD CONSTRAINT chk_runs_cleanup_only_when_terminal CHECK (
    cleanup_started_at IS NULL
    OR status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'ERROR')
);
ALTER TABLE runs ADD CONSTRAINT chk_runs_artifacts_purge_only_when_terminal CHECK (
    artifacts_purge_started_at IS NULL
    OR status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'ERROR')
);
-- purged_at can only ever follow purge_started_at - never set out of order.
ALTER TABLE runs ADD CONSTRAINT chk_runs_artifacts_purged_after_started CHECK (
    artifacts_purged_at IS NULL OR artifacts_purge_started_at IS NOT NULL
);

-- Partial indexes, same reasoning as V3's idx_runs_non_terminal: in steady state almost every row
-- has NULL here, so only the tiny "currently pending cleanup/purge" subset is ever indexed.
CREATE INDEX idx_runs_cleanup_started_at ON runs (cleanup_started_at)
    WHERE cleanup_started_at IS NOT NULL;
CREATE INDEX idx_runs_artifacts_purge_pending ON runs (artifacts_purge_started_at)
    WHERE artifacts_purge_started_at IS NOT NULL AND artifacts_purged_at IS NULL;
