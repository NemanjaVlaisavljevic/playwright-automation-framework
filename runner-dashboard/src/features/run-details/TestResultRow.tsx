import { cx } from "../../components/ui/cx";
import { StatusBadge } from "../../components/ui/StatusBadge";
import { formatDuration, runDurationMs } from "../../domain/duration";
import { CopyButton } from "./CopyButton";
import { FailureDetail } from "./FailureDetail";
import { testRowElementId, type DisplayTest } from "./run-details-view-model";
import { buildRunResultUrl } from "./run-result-target";
import { StepRow } from "./StepRow";
import styles from "./RunDetailsPage.module.css";

export function TestResultRow({
  runId,
  test,
  expanded,
  onToggleExpand,
  artifactsErrorMessage,
}: {
  runId: string;
  test: DisplayTest;
  /** Owned by `TestResultsSection`, not this row - a filtered-out test unmounts and would lose local state. */
  expanded: boolean;
  onToggleExpand: () => void;
  artifactsErrorMessage?: string;
}) {
  const durationMs = runDurationMs(test);
  const hasSteps = test.steps.length > 0;
  // A step-scoped failure preview is collapsed-state only (the step list shows it once expanded);
  // a test-scoped failure has no counterpart in the step list, so it stays visible regardless.
  const showFailurePreview =
    test.primaryFailure !== undefined &&
    (test.primaryFailure.scope === "test" || !expanded);

  return (
    <>
      <tr
        id={testRowElementId(test.testId)}
        // Programmatically focusable so LiveFocusPanel's click-to-jump moves real focus here.
        tabIndex={-1}
        className={cx(
          styles.focusableRow,
          test.status === "FAILED" && styles.failedRow,
        )}
      >
        <td>
          <StatusBadge status={test.status} />
        </td>
        <td>
          {hasSteps ? (
            <button
              type="button"
              className={styles.testNameToggle}
              aria-expanded={expanded}
              onClick={onToggleExpand}
            >
              <span className={styles.disclosureIcon} aria-hidden="true">
                {expanded ? "▾" : "▸"}
              </span>
              {test.testDisplayName}
            </button>
          ) : (
            test.testDisplayName
          )}
        </td>
        <td>{durationMs !== undefined ? formatDuration(durationMs) : "—"}</td>
        <td>
          {/* FailureDetail below is the one place a failure's text is shown; avoid duplicating it here. */}
          {test.primaryFailure !== undefined ? (
            "See failure below"
          ) : test.detail !== undefined ? (
            <details>
              <summary>Detail</summary>
              <pre className={styles.detailText}>{test.detail}</pre>
            </details>
          ) : (
            "—"
          )}
        </td>
        <td>
          <CopyButton
            text={buildRunResultUrl(runId, {
              kind: "test",
              testId: test.testId,
            })}
            label="Copy link"
            ariaLabel={`Copy link to test ${test.testDisplayName}`}
          />
        </td>
      </tr>
      {showFailurePreview && test.primaryFailure !== undefined && (
        <tr>
          <td colSpan={5} className={styles.stepsCell}>
            {test.primaryFailure.scope === "step" && (
              <p className={styles.failurePreviewLabel}>
                <StatusBadge status="FAILED" />
                <span className={styles.stepName}>
                  {test.primaryFailure.stepName}
                </span>
              </p>
            )}
            <FailureDetail
              detail={test.primaryFailure.detail}
              artifacts={test.primaryFailure.artifacts}
              {...(artifactsErrorMessage !== undefined
                ? { artifactsErrorMessage }
                : {})}
            />
          </td>
        </tr>
      )}
      {hasSteps && expanded && (
        <tr>
          <td colSpan={5} className={styles.stepsCell}>
            <ol className={styles.stepList}>
              {test.steps.map((step) => (
                <StepRow
                  key={step.stepId}
                  runId={runId}
                  testId={test.testId}
                  step={step}
                  {...(step.status === "FAILED" &&
                  artifactsErrorMessage !== undefined
                    ? { artifactsErrorMessage }
                    : {})}
                />
              ))}
            </ol>
          </td>
        </tr>
      )}
    </>
  );
}
