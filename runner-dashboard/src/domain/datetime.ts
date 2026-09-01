/**
 * Renders in the viewer's own timezone (not UTC, unlike the raw ISO string the backend sends) -
 * exact enough to be useful, but not what a human reads at a glance. The locale itself is pinned
 * (`"en-US"`), not the viewer's own - deterministic formatting matters more here than adapting to
 * every locale for what is, for now, a single-team internal tool.
 */
export function formatLocalDateTime(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleString("en-US", { dateStyle: "medium", timeStyle: "short" });
}
