package dev.vlaisanem.automation.runner.service.api;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.contract.ArtifactType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Wire representation of an {@link ArtifactManifestEntry} - deliberately never {@code
 * relativePath()}, an internal filesystem detail the manifest's own writer chose, never something a
 * client is handed or can influence. {@link #downloadUrl()} is built only from {@code runId}/{@code
 * artifactId} (both already known-safe identifiers), the one way a client ever reaches the actual
 * file - see {@code ArtifactController#download}. {@link #mediaType()} here is informational only:
 * the download endpoint's actual {@code Content-Type} header is derived from {@link #type()}
 * against a fixed allowlist, never read from this (or the manifest's own) free-text field.
 */
public record ArtifactSummaryResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String artifactId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String testId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String testDisplayName,
    @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String stepId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ArtifactType type,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String mediaType,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sizeBytes,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String downloadUrl) {

  public static ArtifactSummaryResponse from(ArtifactManifestEntry entry) {
    return new ArtifactSummaryResponse(
        entry.artifactId(),
        entry.testId(),
        entry.testDisplayName(),
        entry.stepId(),
        entry.type(),
        entry.mediaType(),
        entry.sizeBytes(),
        entry.createdAt(),
        "/api/v1/runs/" + entry.runId() + "/artifacts/" + entry.artifactId());
  }
}
