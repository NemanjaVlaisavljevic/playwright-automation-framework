import type { ArtifactSummaryResponse } from "../../api/runner-api";
import { artifactTypeLabel } from "../../domain/artifact";
import { firstLine } from "../../domain/text";
import { CopyButton } from "./CopyButton";
import styles from "./RunDetailsPage.module.css";

export interface FailureDetailProps {
  detail: string | undefined;
  artifacts: readonly ArtifactSummaryResponse[];
  /** Set only when the artifacts query itself failed, so the error shows next to this failure. */
  artifactsErrorMessage?: string;
}

/**
 * Renders one failure (a step's, or a whole test's without steps): a one-line summary, the full
 * text on demand, a copy button, and its artifacts. Does not parse the failure text - formatting
 * and redaction already happen server-side in `FailureDetailFormatter`.
 */
export function FailureDetail({
  detail,
  artifacts,
  artifactsErrorMessage,
}: FailureDetailProps) {
  const screenshot = artifacts.find(
    (artifact) => artifact.type === "SCREENSHOT",
  );
  const trace = artifacts.find((artifact) => artifact.type === "TRACE");
  const otherArtifacts = artifacts.filter(
    (artifact) => artifact.type !== "SCREENSHOT" && artifact.type !== "TRACE",
  );

  if (
    detail === undefined &&
    screenshot === undefined &&
    trace === undefined &&
    otherArtifacts.length === 0 &&
    artifactsErrorMessage === undefined
  ) {
    return null;
  }

  return (
    <div className={styles.failureDetail}>
      {detail !== undefined && (
        <>
          <p className={styles.failureSummary}>{firstLine(detail)}</p>
          <div className={styles.failureActions}>
            {/* Skip "View full detail" when it would just duplicate the one-line summary. */}
            {detail !== firstLine(detail) && (
              <details>
                <summary>View full detail</summary>
                <pre className={styles.detailText}>{detail}</pre>
              </details>
            )}
            <CopyButton text={detail} label="Copy failure" />
          </div>
        </>
      )}
      {(screenshot !== undefined ||
        trace !== undefined ||
        otherArtifacts.length > 0) && (
        <div className={styles.failureArtifacts}>
          {screenshot !== undefined && (
            <>
              <a href={screenshot.downloadUrl} target="_blank" rel="noreferrer">
                <img
                  src={screenshot.downloadUrl}
                  alt={`Screenshot for ${screenshot.testDisplayName}`}
                  className={styles.artifactThumbnail}
                  loading="lazy"
                  decoding="async"
                />
              </a>
              <a
                className={styles.downloadLink}
                href={screenshot.downloadUrl}
                target="_blank"
                rel="noreferrer"
              >
                Open screenshot
              </a>
            </>
          )}
          {trace !== undefined && (
            <a className={styles.downloadLink} href={trace.downloadUrl}>
              Download trace
            </a>
          )}
          {otherArtifacts.map((artifact) => (
            <a
              key={artifact.artifactId}
              className={styles.downloadLink}
              href={artifact.downloadUrl}
            >
              {artifactTypeLabel(artifact.type)}
            </a>
          ))}
        </div>
      )}
      {artifactsErrorMessage !== undefined && (
        <p className={styles.failureArtifactsError}>{artifactsErrorMessage}</p>
      )}
    </div>
  );
}
