import styles from "./ProgressBar.module.css";

export interface ProgressBarProps {
  /** 0-100. Values outside that range are clamped. */
  value: number;
  /** Both the visible caption under the bar and the accessible name of the progressbar itself. */
  label: string;
}

/**
 * The fill's `width` is the one place in this codebase's styling an inline style is justified - it
 * is a genuinely per-render dynamic value, not something a CSS Modules class can express.
 */
export function ProgressBar({ value, label }: ProgressBarProps) {
  const clamped = Math.min(100, Math.max(0, value));

  return (
    <div className={styles.wrapper}>
      <div
        className={styles.track}
        role="progressbar"
        aria-valuenow={Math.round(clamped)}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={label}
      >
        <div className={styles.fill} style={{ width: `${clamped}%` }} />
      </div>
      <span className={styles.label}>{label}</span>
    </div>
  );
}
