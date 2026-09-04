import { isTerminalRunStatus, type RunStatus } from "../../domain/run";
import type { ConnectionState } from "../event-stream/use-run-event-stream";
import type { DisplayTest } from "./run-details-view-model";
import styles from "./RunDetailsPage.module.css";

export interface LiveFocusPanelProps {
  tests: readonly DisplayTest[];
  connectionState: ConnectionState;
  /**
   * The run's own effective status - `streamState.runOutcome ?? run.data?.status` (the same
   * REST-fallback preference `buildRunDetailsViewModel` itself uses), `undefined` while the run is
   * still loading or unknown (e.g. a 404). Needed *in addition to* `connectionState`: a dropped
   * `EventSource` can sit in `RECONNECTING` well after the REST fallback has already confirmed the
   * run finished (or that it doesn't exist at all) - a real review finding, since this panel would
   * otherwise keep showing "Last known activity" (or "Active now (0)" for an unknown run)
   * indefinitely for a run that is, in fact, already over.
   */
  runStatus: RunStatus | undefined;
  /**
   * Reveals, scrolls to, and focuses the given test's own row - owned by `TestResultsSection`
   * (see its own `revealTest`), not this component, since C4.4's filters can hide a test this
   * panel still shows as active, and only that section knows how to bring it back on-screen first.
   */
  onSelectTest: (testId: string) => void;
}

/**
 * A quick "what's happening right now" view between Progress and the Tests table, so a viewer
 * doesn't have to hunt a long table for whichever test(s) are currently RUNNING. Purely derived
 * from the same `RunDetailsViewModel` state the table itself renders from - no separate
 * `EventSource`, store, or parallel SSE state of its own (see `RunDetailsPage.tsx`'s single
 * `useRunEventStream` call).
 *
 * Hidden entirely once the connection is `CLOSED` (the run itself is over - see
 * `use-run-event-stream.ts`: `CLOSED` is set exactly on the stream's own terminal `RUN_FINISHED`)
 * or `RECOVERING`/`PROTOCOL_ERROR` (the connection banner above already explains those states -
 * showing data here would imply it's currently reliable when it verifiably isn't: `RECOVERING` has
 * already reset the underlying stream state back to empty for a fresh replay, and `PROTOCOL_ERROR`
 * means the stream is frozen for good) - or once `runStatus` itself is `undefined` (unknown/loading/
 * a 404) or terminal, regardless of what `connectionState` alone says: a dropped `EventSource` can
 * sit in `RECONNECTING` long after the REST fallback already confirmed the run is over.
 * `RECONNECTING` with a still-non-terminal `runStatus` is the one case that keeps showing its last
 * known data - nothing about a dropped transport invalidates what was already known - only the
 * heading changes, so a viewer can tell it may no longer be current.
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
  // Stable order for free: `tests` is already sorted by `firstSequence` (see
  // `buildRunDetailsViewModel`), and `INTERRUPTED` (a display-only relabeling, never a real wire
  // status) never equals `"RUNNING"`, so a reconciled-away test never shows here as still active.
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
        // Compact, single line - a taller empty state here would make the panel visibly jump in
        // and out of layout every time the run goes briefly idle between tests.
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
      {/* A separate, visually-hidden live region - the clickable list above is not itself
          aria-live, so its own presence/reordering never doubles up with these announcements, and
          the interactive panel itself is never wrapped in role="status". */}
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
