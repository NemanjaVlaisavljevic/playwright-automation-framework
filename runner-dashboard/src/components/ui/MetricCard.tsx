import { cx } from "./cx";
import styles from "./MetricCard.module.css";

export type MetricTone = "neutral" | "info" | "success" | "danger" | "warning";

export interface MetricCardProps {
  label: string;
  value: string | number;
  tone?: MetricTone;
}

export function MetricCard({
  label,
  value,
  tone = "neutral",
}: MetricCardProps) {
  return (
    <div className={cx(styles.card, styles[tone])}>
      <p className={styles.value}>{value}</p>
      <p className={styles.label}>{label}</p>
    </div>
  );
}
