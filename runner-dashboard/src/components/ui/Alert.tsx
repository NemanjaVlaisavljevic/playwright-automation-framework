import type { ReactNode } from "react";
import { cx } from "./cx";
import styles from "./Alert.module.css";

export type AlertTone = "danger" | "warning" | "info" | "success";

const ICON_BY_TONE: Record<AlertTone, string> = {
  danger: "⛔",
  warning: "⚠",
  info: "ℹ",
  success: "✓",
};

export interface AlertProps {
  tone?: AlertTone;
  children: ReactNode;
}

/**
 * `role="alert"` on every tone, not just `danger` - a screen reader announcing an informational or
 * success message immediately (rather than waiting to be discovered) is exactly what the existing
 * call sites already relied on before this component existed. The icon is `aria-hidden` and purely
 * reinforces the tone alongside color/text - it is never the only signal.
 */
export function Alert({ tone = "danger", children }: AlertProps) {
  return (
    <div role="alert" className={cx(styles.alert, styles[tone])}>
      <span aria-hidden="true" className={styles.icon}>
        {ICON_BY_TONE[tone]}
      </span>
      <span>{children}</span>
    </div>
  );
}
