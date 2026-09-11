package dev.vlaisanem.automation.runner.service.exception;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;

/**
 * Thrown when an incoming {@link ArtifactManifestEntry} reuses an existing {@code artifact_id} but
 * with different metadata, so {@code ON CONFLICT ... DO NOTHING} would silently drop it. Caught and
 * logged by {@code ArtifactIngestionService}; never served to an HTTP client.
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
