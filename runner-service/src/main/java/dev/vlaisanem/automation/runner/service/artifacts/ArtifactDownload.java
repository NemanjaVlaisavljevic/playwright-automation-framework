package dev.vlaisanem.automation.runner.service.artifacts;

import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import java.nio.file.Path;

/**
 * A manifest entry paired with its already-validated, already-resolved real file path on disk - see
 * {@code ArtifactService#download}. The controller layer never sees {@link
 * ArtifactManifestEntry#relativePath()} or constructs a {@link Path} itself.
 */
public record ArtifactDownload(ArtifactManifestEntry entry, Path file) {}
