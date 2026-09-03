import type { ArtifactSummaryResponse } from "../../api/runner-api";
import { formatBytes } from "../../domain/bytes";
import { artifactTypeLabel } from "../../domain/artifact";
import styles from "./RunDetailsPage.module.css";

export function ArtifactsSection({
  runId,
  artifacts,
}: {
  runId: string;
  artifacts: readonly ArtifactSummaryResponse[];
}) {
  return (
    <div className={styles.tableScroll}>
      <table className={styles.table}>
        <caption className="visually-hidden">Artifacts for run {runId}</caption>
        <thead>
          <tr>
            <th>Test</th>
            <th>Type</th>
            <th>Size</th>
            <th>Artifact</th>
          </tr>
        </thead>
        <tbody>
          {artifacts.map((artifact) => (
            <ArtifactRow key={artifact.artifactId} artifact={artifact} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ArtifactRow({ artifact }: { artifact: ArtifactSummaryResponse }) {
  return (
    <tr>
      <td>{artifact.testDisplayName}</td>
      <td>{artifactTypeLabel(artifact.type)}</td>
      <td>{formatBytes(artifact.sizeBytes)}</td>
      <td>
        {artifact.type === "SCREENSHOT" ? (
          <a href={artifact.downloadUrl} target="_blank" rel="noreferrer">
            <img
              src={artifact.downloadUrl}
              alt={`Screenshot for ${artifact.testDisplayName}`}
              className={styles.artifactThumbnail}
              loading="lazy"
              decoding="async"
            />
          </a>
        ) : (
          <a className={styles.downloadLink} href={artifact.downloadUrl}>
            Download {artifactTypeLabel(artifact.type).toLowerCase()}
          </a>
        )}
      </td>
    </tr>
  );
}
