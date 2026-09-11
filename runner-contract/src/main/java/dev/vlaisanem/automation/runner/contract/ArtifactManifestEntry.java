package dev.vlaisanem.automation.runner.contract;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Contract between the test JVM that produces a run's artifacts (screenshots, Playwright traces,
 * videos) and runner-service, which later serves them - the same role {@link RunnerEvent} plays for
 * lifecycle events. Framework-agnostic: no serialization annotations, no filesystem I/O.
 *
 * @param artifactId opaque, stable identifier for this artifact - never a filesystem path.
 * @param runId the run this artifact belongs to; matches the {@code runId} on that run's {@link
 *     RunnerEvent}s.
 * @param testId JUnit's {@code TestIdentifier.getUniqueId()}; matches the {@code testId} on that
 *     test's {@link RunnerEvent}s.
 * @param testDisplayName human-readable test name, mirroring {@link RunnerEvent#testDisplayName()}.
 * @param stepId the step this artifact belongs to, or {@code null} when not step-scoped.
 * @param relativePath path to the artifact file, relative to the run's artifacts root - never
 *     absolute, no {@code ..} segment, always {@code /}-separated.
 * @param mediaType the artifact file's MIME type (e.g. {@code image/png}).
 * @param sizeBytes size in bytes, captured once the file is fully written.
 * @param createdAt when the artifact file finished writing.
 */
public record ArtifactManifestEntry(
    String schemaVersion,
    String artifactId,
    String runId,
    String testId,
    String testDisplayName,
    String stepId,
    ArtifactType type,
    String relativePath,
    String mediaType,
    long sizeBytes,
    Instant createdAt) {

  public static final String CURRENT_SCHEMA_VERSION = "1.1";

  // Restrictive beyond non-blank: embedded verbatim in a downloadUrl, a REST path segment, and a
  // Content-Disposition header (see ArtifactController) - unsafe chars could break those or open
  // a header-injection window.
  private static final Pattern ARTIFACT_ID_PATTERN =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}");

  public ArtifactManifestEntry {
    if (!CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "Unsupported ArtifactManifestEntry schemaVersion: " + schemaVersion);
    }
    requireNonBlank(artifactId, "artifactId");
    validateArtifactId(artifactId);
    requireNonBlank(runId, "runId");
    requireNonBlank(testId, "testId");
    requireNonBlank(testDisplayName, "testDisplayName");
    if (stepId != null && stepId.isBlank()) {
      throw new IllegalArgumentException("stepId must not be blank when present");
    }
    Objects.requireNonNull(type, "type must not be null");
    requireNonBlank(relativePath, "relativePath");
    validateRelativePath(relativePath);
    requireNonBlank(mediaType, "mediaType");
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("sizeBytes must not be negative, was " + sizeBytes);
    }
    Objects.requireNonNull(createdAt, "createdAt must not be null");
  }

  private static void validateArtifactId(String artifactId) {
    if (!ARTIFACT_ID_PATTERN.matcher(artifactId).matches()) {
      throw new IllegalArgumentException(
          "artifactId must match " + ARTIFACT_ID_PATTERN.pattern() + ": " + artifactId);
    }
  }

  private static void requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
  }

  /**
   * Rejects an absolute path, a {@code ..} segment, or a {@code \} separator - any could escape the
   * artifacts root.
   */
  private static void validateRelativePath(String relativePath) {
    if (relativePath.startsWith("/") || relativePath.matches("^[A-Za-z]:.*")) {
      throw new IllegalArgumentException("relativePath must not be absolute: " + relativePath);
    }
    if (relativePath.contains("\\")) {
      throw new IllegalArgumentException(
          "relativePath must use '/' as its separator, not '\\': " + relativePath);
    }
    for (String segment : relativePath.split("/")) {
      if (segment.equals("..")) {
        throw new IllegalArgumentException(
            "relativePath must not contain a '..' segment: " + relativePath);
      }
    }
  }
}
