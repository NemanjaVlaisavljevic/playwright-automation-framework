import { StatusBadge } from "../../components/ui/StatusBadge";
import { artifactTypeLabel } from "../../domain/artifact";
import { formatDuration, runDurationMs } from "../../domain/duration";
import type { DisplayStep } from "./run-details-view-model";
import styles from "./RunDetailsPage.module.css";

export function StepRow({ step }: { step: DisplayStep }) {
  const durationMs = runDurationMs(step);
  return (
    <li className={styles.stepItem}>
      <StatusBadge status={step.status} />
      <span className={styles.stepName}>{step.stepName}</span>
      {durationMs !== undefined && (
        <span className={styles.stepDuration}>
          {formatDuration(durationMs)}
        </span>
      )}
      {step.artifacts.map((artifact) => (
        <a
          key={artifact.artifactId}
          className={styles.downloadLink}
          href={artifact.downloadUrl}
        >
          {artifactTypeLabel(artifact.type)}
        </a>
      ))}
      {step.detail !== undefined && (
        <details>
          <summary>Detail</summary>
          <pre className={styles.detailText}>{step.detail}</pre>
        </details>
      )}
    </li>
  );
}
