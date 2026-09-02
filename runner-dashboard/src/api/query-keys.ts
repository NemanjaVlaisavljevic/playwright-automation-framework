export const queryKeys = {
  health: ["runner-health"] as const,
  capabilities: ["runner-capabilities"] as const,
  runs: ["runs"] as const,
  run: (runId: string) => ["runs", runId] as const,
  runArtifacts: (runId: string) => ["runs", runId, "artifacts"] as const,
  // Extends, rather than replaces, `runArtifacts`'s own key array - invalidating the base
  // `runArtifacts(runId)` key still matches every per-test filter too (TanStack Query's own
  // prefix-matching), so a per-test drill-down query never has to be invalidated separately.
  runArtifactsForTest: (runId: string, testId: string) =>
    [...queryKeys.runArtifacts(runId), { testId }] as const,
};
