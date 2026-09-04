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
  /**
   * Owned by `TestResultsSection`, not this row - a filtered-out test unmounts, so any expand
   * state kept in the row itself would be lost the moment a filter hides it and reappear reset
   * once it's shown again (see the C4.4 spec's own reasoning for moving this state up).
   */
  expanded: boolean;
  onToggleExpand: () => void;
  artifactsErrorMessage?: string;
}) {
  const durationMs = runDurationMs(test);
  const hasSteps = test.steps.length > 0;
  // A "step"-scoped failure preview is specifically a *collapsed-state* affordance - once the row
  // is expanded, that same failed step already renders richly as part of the full step list, and
  // showing it a second time here would just be duplication. A "test"-scoped failure (no step
  // explains it - see `DisplayTestFailure`'s own doc comment) has no such counterpart anywhere in
  // the step list, expanded or not, so it must stay visible regardless of expand state - hiding it
  // on expand would strand its detail/screenshot/trace/Copy-failure with nowhere else to appear.
  const showFailurePreview =
    test.primaryFailure !== undefined &&
    (test.primaryFailure.scope === "test" || !expanded);

  return (
    <>
      <tr
        id={testRowElementId(test.testId)}
        // Programmatically focusable (never part of normal tab order) so `LiveFocusPanel`'s
        // click-to-jump can move real keyboard/AT focus here, not just scroll the page to it - and
        // `.focusableRow`'s own `scroll-margin-top` keeps the row clear of the sticky panel above it
        // once scrolled into view.
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
          {/* `FailureDetail` below is the one place a failure's own text is shown - this legacy
              disclosure would otherwise duplicate that exact same content (word-for-word for a
              single-line detail, a second redundant disclosure for a multi-line one). */}
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
