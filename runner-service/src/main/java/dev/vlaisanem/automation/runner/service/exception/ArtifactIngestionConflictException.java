package dev.vlaisanem.automation.runner.service.exception;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;

/**
 * Thrown when {@code artifacts.artifact_id}'s primary key already holds a row for an incoming
 * {@link ArtifactManifestEntry}, but that existing row's own fields do not match the incoming one
 * exactly - {@code ON CONFLICT (artifact_id) DO NOTHING}'s idempotency is only actually correct
 * when the "conflicting" write really is a re-read of the same entry, never when the same id
 * legitimately means two different things (a different {@code runId}, path, type, size, or any
 * other metadata field). Silently accepting the second write with {@code DO NOTHING} would durably
 * lose that second artifact's own metadata with no signal at all - this is deliberately its own
 * exception instead, caught and logged (never served to an HTTP client - ingestion is always a
 * background concern, not a request-path one) by {@code ArtifactIngestionService}.
 */
public class ArtifactIngestionConflictException extends RuntimeException {

  public ArtifactIngestionConflictException(
      String artifactId, ArtifactManifestEntry existing, ArtifactManifestEntry incoming) {
    super(
        "artifactId "
            + artifactId
            + " already exists with different metadata - existing="
            + existing
            + ", incoming="
            + incoming);
  }
}
