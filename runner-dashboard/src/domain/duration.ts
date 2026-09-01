/** Whole seconds/minutes only - this is a dashboard list column, not a stopwatch. */
export function formatDuration(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return minutes > 0 ? `${minutes}m ${seconds}s` : `${seconds}s`;
}

/**
 * `undefined` before a run has actually started (`startedAt` absent - still `QUEUED`), since
 * there's nothing to measure yet. Once started but not yet finished, measures against `now`
 * (defaults to `Date.now()`, overridable so this stays deterministic in tests) - this is what makes
 * a running row's duration keep advancing on each poll-driven re-render without a separate timer.
 */
export function runDurationMs(
  run: { startedAt?: string; finishedAt?: string },
  now: number = Date.now(),
): number | undefined {
  if (run.startedAt === undefined) {
    return undefined;
  }
  const start = Date.parse(run.startedAt);
  const end = run.finishedAt !== undefined ? Date.parse(run.finishedAt) : now;
  return end - start;
}
