export const queryKeys = {
  health: ["runner-health"] as const,
  capabilities: ["runner-capabilities"] as const,
  runs: ["runs"] as const,
  run: (runId: string) => ["runs", runId] as const,
};
