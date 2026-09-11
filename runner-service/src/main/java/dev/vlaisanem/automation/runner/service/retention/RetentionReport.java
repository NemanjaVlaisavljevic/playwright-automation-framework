package dev.vlaisanem.automation.runner.service.retention;

/**
 * The typed result of one {@link RetentionService#sweep} pass, returned to both the scheduler
 * (logged) and the REST controller (serialized as-is). Never throws on a per-item failure.
 *
 * @param dryRun {@code true} if this report only counted candidates - no claim, delete, or write of
 *     any kind happened.
 * @param runCandidateCount how many runs were eligible for full cleanup this sweep (new + resumed
 *     from a prior crash).
 * @param runDeletedCount how many of those were actually fully deleted (row + every on-disk file).
 * @param runFailedCount how many failed partway through - still tombstoned, retried on the next
 *     sweep.
 * @param artifactPurgeCandidateCount how many runs were eligible for artifact-only purge this sweep
 *     (excluding any run also present in the full-cleanup candidate set).
 * @param artifactPurgeCompletedCount how many of those actually completed (directory removed,
 *     {@code artifacts} rows deleted, {@code artifacts_purged_at} set).
 * @param artifactPurgeFailedCount how many failed partway through - still claimed, retried on the
 *     next sweep.
 * @param bytesFreed total size (measured before deletion) of every directory actually removed this
 *     sweep, across both full cleanup and artifact purge.
 * @param skipped {@code true} when this call did nothing because another real sweep was already in
 *     progress - every count above is {@code 0} in that case, not a signal that nothing was
 *     eligible. Always {@code false} for a dry-run preview.
 */
public record RetentionReport(
    boolean dryRun,
    int runCandidateCount,
    int runDeletedCount,
    int runFailedCount,
    int artifactPurgeCandidateCount,
    int artifactPurgeCompletedCount,
    int artifactPurgeFailedCount,
    long bytesFreed,
    boolean skipped) {}
