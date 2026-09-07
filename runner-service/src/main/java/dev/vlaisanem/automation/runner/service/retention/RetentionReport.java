package dev.vlaisanem.automation.runner.service.retention;

/**
 * D4.1 - the typed result of one {@link RetentionService#sweep} pass, returned to both the
 * scheduler (logged) and the REST controller (serialized as-is). Never throws on a per-item failure
 * - see {@link RetentionService}'s own Javadoc for why a background maintenance job isolates and
 * counts failures instead of aborting.
 *
 * @param dryRun {@code true} if this report only counted candidates - no claim, delete, or write of
 *     any kind happened.
 * @param runCandidateCount how many runs were eligible for full cleanup this sweep (new + resumed
 *     from a prior crash).
 * @param runDeletedCount how many of those were actually fully deleted (row + every on-disk file).
 * @param runFailedCount how many failed partway through - still tombstoned, retried on the next
 *     sweep.
 * @param artifactPurgeCandidateCount how many runs were eligible for artifact-only purge this sweep
 *     (excluding any run also present in the full-cleanup candidate set - see {@link
 *     RetentionService}'s own precedence rule).
 * @param artifactPurgeCompletedCount how many of those actually completed (directory removed,
 *     {@code artifacts} rows deleted, {@code artifacts_purged_at} set).
 * @param artifactPurgeFailedCount how many failed partway through - still claimed, retried on the
 *     next sweep.
 * @param bytesFreed total size (measured before deletion) of every directory actually removed this
 *     sweep, across both full cleanup and artifact purge.
 * @param skipped (D4.1 review round) {@code true} when this call did nothing at all because another
 *     real sweep was already in progress in this process at the moment it was called - see {@link
 *     RetentionService}'s own in-process concurrency guard. Every count above is {@code 0} in that
 *     case; this is not a signal that there was nothing eligible, only that this particular call
 *     was not the one to check. Always {@code false} for a dry-run preview, which never contends
 *     with a real sweep's lock.
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
