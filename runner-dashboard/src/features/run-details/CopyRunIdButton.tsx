import { CopyButton } from "./CopyButton";

/**
 * A run ID is long enough (a UUID) that selecting it by hand is fiddly - this copies it verbatim to
 * the clipboard and briefly confirms success in the button's own label, rather than a separate toast
 * that could be missed or outlive the page navigating away.
 */
export function CopyRunIdButton({ runId }: { runId: string }) {
  return <CopyButton text={runId} />;
}
