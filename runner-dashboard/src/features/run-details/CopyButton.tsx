import { useCopyToClipboard } from "./use-copy-to-clipboard";
import styles from "./RunDetailsPage.module.css";

export interface CopyButtonProps {
  text: string;
  label?: string;
  copiedLabel?: string;
  /**
   * Overrides the button's accessible name (defaults to the visible `label`) - lets a compact
   * visible label ("Copy") carry a more specific spoken name (e.g. "Copy link to test
   * BookingJourneyTest") without repeating that full text visibly on every row - see C4.5's
   * per-row "Copy link" buttons.
   */
  ariaLabel?: string;
}

/**
 * Generic "click to copy, briefly confirm" button - originally built for the run ID, now the shared
 * building block for anywhere else a value needs the same one-tap-copy affordance (e.g. a failure's
 * own detail text - see `FailureDetail.tsx`).
 */
export function CopyButton({
  text,
  label = "Copy",
  copiedLabel = "Copied!",
  ariaLabel,
}: CopyButtonProps) {
  const { copied, copy } = useCopyToClipboard();

  return (
    <>
      <button
        type="button"
        className={styles.copyButton}
        aria-label={ariaLabel}
        onClick={() => copy(text)}
      >
        {copied ? copiedLabel : label}
      </button>
      {/*
       * A static `aria-label` (when given) overrides the button's own visible text for assistive
       * tech, so the "Copied!" text change is otherwise invisible to a screen reader - a real
       * review finding. A separate, visually-hidden `aria-live="polite"` region announces the
       * confirmation instead of making the label itself dynamic, so a button described as "Copy
       * link to test X" keeps saying exactly that (not "Copied!", dropping the very context that
       * makes it findable) - the confirmation is a transient side note, not a redefinition of what
       * the button does.
       */}
      <span className="visually-hidden" aria-live="polite">
        {copied ? "Copied to clipboard." : ""}
      </span>
    </>
  );
}
