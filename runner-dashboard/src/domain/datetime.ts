/**
 * Renders in the viewer's own timezone, not UTC. Locale is pinned to `"en-US"` for deterministic
 * formatting rather than adapting to the viewer's locale.
 */
export function formatLocalDateTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleString("en-US", { dateStyle: "medium", timeStyle: "short" });
}
