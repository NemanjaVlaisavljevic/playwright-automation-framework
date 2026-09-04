import { cx } from "../../components/ui/cx";
import { StatusBadge } from "../../components/ui/StatusBadge";
import { formatDuration, runDurationMs } from "../../domain/duration";
import { CopyButton } from "./CopyButton";
import { FailureDetail } from "./FailureDetail";
import { stepRowElementId, type DisplayStep } from "./run-details-view-model";
import { buildRunResultUrl } from "./run-result-target";
import styles from "./RunDetailsPage.module.css";

export function StepRow({
  runId,
  testId,
  step,
  artifactsErrorMessage,
}: {
  runId: string;
  testId: string;
  step: DisplayStep;
  artifactsErrorMessage?: string;
}) {
  const durationMs = runDurationMs(step);
  return (
    <li
      id={stepRowElementId(testId, step.stepId)}
      // Same programmatically-focusable pattern as the test row above it (see
      // `TestResultRow.tsx`) - a deep link or Live Focus reveal can target a step directly.
      tabIndex={-1}
      className={cx(styles.stepItem, styles.focusableRow)}
    >
      <StatusBadge status={step.status} />
      <span className={styles.stepName}>{step.stepName}</span>
      {durationMs !== undefined && (
        <span className={styles.stepDuration}>
          {formatDuration(durationMs)}
        </span>
      )}
      <CopyButton
        text={buildRunResultUrl(runId, {
          kind: "step",
          testId,
          stepId: step.stepId,
        })}
        label="Copy link"
        ariaLabel={`Copy link to step ${step.stepName}`}
      />
      <FailureDetail
        detail={step.detail}
        artifacts={step.artifacts}
        {...(artifactsErrorMessage !== undefined
          ? { artifactsErrorMessage }
          : {})}
      />
    </li>
  );
}
