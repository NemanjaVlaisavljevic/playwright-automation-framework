package dev.vlaisanem.automation.runner.contract;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The process-boundary contract between the test JVM that produces a run's artifacts (screenshots,
 * Playwright traces, videos) and the runner-service process that later serves them - the same role
 * {@link RunnerEvent} plays for run/test lifecycle events. Deliberately framework-agnostic (no
 * serialization annotations, no filesystem I/O): the writer (the main automation framework) and the
 * reader (runner-service) each configure their own JSON mapper and own how the file itself is
 * opened/appended/read.
 *
 * @param artifactId opaque, stable identifier for this one artifact - never a filesystem path.
 * @param runId the run this artifact belongs to - always identical to the {@code runId} carried on
 *     that run's own {@link RunnerEvent}s, so a reader can correlate the two streams.
 * @param testId JUnit's {@code TestIdentifier.getUniqueId()} (equivalently, the JUnit Jupiter
 *     {@code ExtensionContext.getUniqueId()} for the same test execution - both are views over the
 *     same underlying {@code TestDescriptor}) - always identical to the {@code testId} carried on
 *     that test's own {@link RunnerEvent}s.
 * @param testDisplayName human-readable test name, mirroring {@link RunnerEvent#testDisplayName()}.
 * @param stepId the step this artifact belongs to, mirroring {@link RunnerEvent#stepId()}, or
 *     {@code null} when the artifact was captured for a test that never used the {@code Steps} API
 *     (or does not (yet) correlate to one specific step).
 * @param relativePath path to the artifact file, relative to that run's own artifacts root
 *     directory - never absolute, never containing a {@code ..} segment, and always using {@code /}
 *     as its separator (even when the entry was written on Windows) so a reader never needs to know
 *     or care what platform produced it. A reader resolves this against a name it already knows to
 *     be safe on its own side (the run's own artifacts root) - this type only guarantees the path
 *     segment itself cannot escape that root once resolved.
 * @param mediaType the artifact file's MIME type (e.g. {@code image/png}).
 * @param sizeBytes the artifact file's size in bytes, captured once the file was fully written.
 * @param createdAt when the artifact file was finished writing.
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

  // Deliberately restrictive, not just non-blank: this value is embedded verbatim into a
  // downloadUrl, used as a REST path segment, and concatenated into a Content-Disposition header
  // (see ArtifactController) - a value containing '/', '"', CR/LF, or other control characters
  // could
  // produce an invalid URL/header or open a header-injection window, depending on the servlet
  // container. The writer's own artifactId is always a random UUID, which already satisfies this.
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
   * Rejects anything that could escape or misidentify the run's own artifacts root once a reader
   * resolves this path against it - an absolute path (POSIX or a Windows drive letter), a {@code
   * ..} segment anywhere, or a {@code \} separator (which would otherwise resolve completely
   * differently, or not at all, on the platform actually reading this entry).
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
