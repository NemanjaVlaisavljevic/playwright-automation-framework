import { useState } from "react";
import { StatusBadge } from "../../components/ui/StatusBadge";
import { formatDuration, runDurationMs } from "../../domain/duration";
import type { DisplayTest } from "./run-details-view-model";
import { StepRow } from "./StepRow";
import styles from "./RunDetailsPage.module.css";

export function TestResultRow({ test }: { test: DisplayTest }) {
  // `undefined` means the user hasn't explicitly chosen yet - the row then defaults to open while
  // the test is still (genuinely) RUNNING - an INTERRUPTED test is never "still running", so this
  // check against the view model's own reconciled status is what stops a run-ended test from
  // staying auto-expanded forever - and closed once it isn't. Once the user does click, that
  // explicit choice is what's shown from then on, in either direction (open or closed) and across
  // the test's own RUNNING -> terminal transition - a review finding was that force-collapsing (or
  // force-reopening) a row the user had deliberately set would be more surprising than just leaving
  // their choice alone.
  const [manualExpanded, setManualExpanded] = useState<boolean | undefined>(
    undefined,
  );
  const expanded = manualExpanded ?? test.status === "RUNNING";
  const durationMs = runDurationMs(test);
  const hasSteps = test.steps.length > 0;
  return (
    <>
      <tr className={test.status === "FAILED" ? styles.failedRow : undefined}>
        <td>
          <StatusBadge status={test.status} />
        </td>
        <td>
          {hasSteps ? (
            <button
              type="button"
              className={styles.testNameToggle}
              aria-expanded={expanded}
              onClick={() => setManualExpanded(!expanded)}
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
          {test.detail !== undefined ? (
            <details>
              <summary>Detail</summary>
              <pre className={styles.detailText}>{test.detail}</pre>
            </details>
          ) : (
            "—"
          )}
        </td>
      </tr>
      {hasSteps && expanded && (
        <tr>
          <td colSpan={4} className={styles.stepsCell}>
            <ol className={styles.stepList}>
              {test.steps.map((step) => (
                <StepRow key={step.stepId} step={step} />
              ))}
            </ol>
          </td>
        </tr>
      )}
    </>
  );
}
