export const queryKeys = {
  health: ["runner-health"] as const,
  performanceBaseline: ["performance-baseline"] as const,
  currentUser: ["current-user"] as const,
  csrf: ["csrf-token"] as const,
  capabilities: ["runner-capabilities"] as const,
  publicTestCatalog: (environment: string) =>
    ["public-test-catalog", environment] as const,
  runs: ["runs"] as const,
  run: (runId: string) => ["runs", runId] as const,
  runArtifacts: (runId: string) => ["runs", runId, "artifacts"] as const,
  // Extends `runArtifacts`'s key array so invalidating the base key also matches this one
  // (TanStack Query prefix-matching).
  runArtifactsForTest: (runId: string, testId: string) =>
    [...queryKeys.runArtifacts(runId), { testId }] as const,
};
