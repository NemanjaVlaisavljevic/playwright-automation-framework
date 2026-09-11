import { isTerminalRunStatus, type RunStatus } from "../../domain/run";
import type { ConnectionState } from "../event-stream/use-run-event-stream";
import type { DisplayTest } from "./run-details-view-model";
import styles from "./RunDetailsPage.module.css";

export interface LiveFocusPanelProps {
  tests: readonly DisplayTest[];
  connectionState: ConnectionState;
  /**
   * The run's effective status; `undefined` while loading/unknown. Needed in addition to
   * `connectionState` since a dropped EventSource can sit in `RECONNECTING` after REST already
   * confirmed the run is over.
   */
  runStatus: RunStatus | undefined;
  /** Reveals/scrolls/focuses the test's row - owned by `TestResultsSection` since its filters can hide it. */
  onSelectTest: (testId: string) => void;
}

/**
 * "What's happening right now" view between Progress and the Tests table, for whichever test(s)
 * are currently RUNNING. Derived from the same view-model state the table renders from.
 *
 * Hidden once the connection is `CLOSED`/`RECOVERING`/`PROTOCOL_ERROR`, or `runStatus` is
 * `undefined` or terminal - covers a dropped EventSource sitting in `RECONNECTING` after REST
 * already confirmed the run is over. `RECONNECTING` with a non-terminal `runStatus` keeps showing
 * last-known data, with only the heading changing to signal it may be stale.
 */
export function LiveFocusPanel({
  tests,
  connectionState,
  runStatus,
  onSelectTest,
}: LiveFocusPanelProps) {
  if (
    connectionState === "CLOSED" ||
    connectionState === "RECOVERING" ||
    connectionState === "PROTOCOL_ERROR" ||
    runStatus === undefined ||
    isTerminalRunStatus(runStatus)
  ) {
    return null;
  }

  const isReconnecting = connectionState === "RECONNECTING";
  // `tests` is already sorted by firstSequence; INTERRUPTED never equals RUNNING.
  const activeTests = tests.filter((test) => test.status === "RUNNING");

  return (
    <section
      className={styles.liveFocusPanel}
      aria-labelledby="live-focus-panel-heading"
    >
      <h2 id="live-focus-panel-heading" className={styles.sectionTitle}>
        {isReconnecting
          ? "Last known activity"
          : `Active now (${activeTests.length})`}
      </h2>
      {activeTests.length === 0 ? (
        // Compact single line so the panel doesn't jump in layout when briefly idle.
        <p className={styles.liveFocusEmpty}>Waiting for the next test…</p>
      ) : (
        <ul className={styles.liveFocusList}>
          {activeTests.map((test) => (
            <li key={test.testId}>
              <button
                type="button"
                className={styles.liveFocusItem}
                onClick={() => onSelectTest(test.testId)}
              >
                <span className={styles.liveFocusTestName}>
                  {test.testDisplayName}
                </span>
                <span className={styles.liveFocusStepName}>
                  {activeStepLabel(test)}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {/* Separate hidden live region: the list itself isn't aria-live. */}
      <p className="visually-hidden" aria-live="polite">
        {liveRegionText(activeTests, isReconnecting)}
      </p>
    </section>
  );
}

function activeStepLabel(test: DisplayTest): string {
  const activeStep = test.steps.find((step) => step.status === "RUNNING");
  return activeStep?.stepName ?? "Waiting for next reported step…";
}

function liveRegionText(
  activeTests: readonly DisplayTest[],
  isReconnecting: boolean,
): string {
  if (activeTests.length === 0) {
    return isReconnecting
      ? "Last known activity: no active tests."
      : "Waiting for the next test.";
  }
  const prefix = isReconnecting ? "Last known activity" : "Now running";
  const parts = activeTests.map((test) => {
    const activeStep = test.steps.find((step) => step.status === "RUNNING");
    return activeStep !== undefined
      ? `${test.testDisplayName}, step ${activeStep.stepName}`
      : `${test.testDisplayName}, waiting for the next reported step`;
  });
  return `${prefix}: ${parts.join("; ")}.`;
}
