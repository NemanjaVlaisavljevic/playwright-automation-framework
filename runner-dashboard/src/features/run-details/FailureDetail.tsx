import type { ArtifactSummaryResponse } from "../../api/runner-api";
import { artifactTypeLabel } from "../../domain/artifact";
import { firstLine } from "../../domain/text";
import { CopyButton } from "./CopyButton";
import styles from "./RunDetailsPage.module.css";

export interface FailureDetailProps {
  detail: string | undefined;
  artifacts: readonly ArtifactSummaryResponse[];
  /**
   * Set only when the artifacts *query itself* failed (see `RunDetailsPage.tsx`) - scoped here so a
   * broken artifacts fetch is visible right where it matters (this specific failure) rather than
   * only as a single banner elsewhere on the page that a viewer has to connect back to this test by
   * themselves.
   */
  artifactsErrorMessage?: string;
}

/**
 * Everywhere a single failure (a step's own, or a whole test's when it never used the `Steps` API)
 * needs to be understood without hunting the rest of the page: a one-line summary always visible,
 * the full redacted text available verbatim on demand, a one-click copy of that same full text, and
 * whichever of its own screenshot/trace/other artifacts exist. Deliberately does not parse the
 * failure text into a structured object (message/type/stack frames) - the first line is a plain
 * string slice, and the full text is shown completely verbatim; `FailureDetailFormatter` on the
 * backend already does the real formatting/redaction work.
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
            {/* Nothing to disclose beyond the summary when the detail is only one line - showing
                "View full detail" over an identical copy of the same text would be pointless, and
                would put that same text on the page twice. */}
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
