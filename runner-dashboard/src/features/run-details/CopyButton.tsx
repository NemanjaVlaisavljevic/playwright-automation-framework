import { useCopyToClipboard } from "./use-copy-to-clipboard";
import styles from "./RunDetailsPage.module.css";

export interface CopyButtonProps {
  text: string;
  label?: string;
  copiedLabel?: string;
  /** Overrides the accessible name (defaults to visible `label`) for a more specific spoken name. */
  ariaLabel?: string;
}

/** Generic "click to copy, briefly confirm" button. */
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
      {/* A static aria-label hides the "Copied!" text change from screen readers, so a separate
          live region announces it instead of making the label itself dynamic. */}
      <span className="visually-hidden" aria-live="polite">
        {copied ? "Copied to clipboard." : ""}
      </span>
    </>
  );
}
