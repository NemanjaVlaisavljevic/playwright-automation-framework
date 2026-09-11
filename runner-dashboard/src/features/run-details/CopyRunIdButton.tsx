import { CopyButton } from "./CopyButton";

/** Copies the run ID (a UUID) to the clipboard, confirming success in the button's own label. */
export function CopyRunIdButton({ runId }: { runId: string }) {
  return <CopyButton text={runId} />;
}
