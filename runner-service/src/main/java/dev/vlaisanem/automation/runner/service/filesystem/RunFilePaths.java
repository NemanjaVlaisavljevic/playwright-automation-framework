package dev.vlaisanem.automation.runner.service.filesystem;

import java.nio.file.Path;

/** Builds run-owned paths only from a single, separator-free run-id segment. */
public final class RunFilePaths {

  private static final String MANIFEST_FILE_NAME = "manifest.jsonl";
  private static final String PROCESS_LOG_SUFFIX = ".log";

  private RunFilePaths() {}

  public static String requireSafeRunId(String runId) {
    // Keep this as String.matches with a compile-time regex: CodeQL recognizes a character class
    // that excludes '.', '/' and '\\' as a path-injection barrier. The broader slug shape also
    // preserves deterministic performance fixtures; production-created ids remain UUIDs.
    if (runId == null || !runId.matches("[A-Za-z0-9_-]{1,64}")) {
      throw invalidRunId();
    }
    return runId;
  }

  public static Path artifactsDirectory(Path artifactsRoot, String runId) {
    return directChild(artifactsRoot, requireSafeRunId(runId));
  }

  public static Path artifactManifest(Path artifactsRoot, String runId) {
    return artifactsDirectory(artifactsRoot, runId).resolve(MANIFEST_FILE_NAME);
  }

  public static Path processLog(Path logsRoot, String runId) {
    return directChild(logsRoot, requireSafeRunId(runId) + PROCESS_LOG_SUFFIX);
  }

  private static Path directChild(Path root, String childName) {
    Path normalizedRoot = root.toAbsolutePath().normalize();
    Path candidate = normalizedRoot.resolve(childName).normalize();
    if (!normalizedRoot.equals(candidate.getParent())) {
      throw new IllegalArgumentException("Refusing to build a path outside its configured root");
    }
    return candidate;
  }

  private static IllegalArgumentException invalidRunId() {
    return new IllegalArgumentException("runId must be a single safe path segment");
  }
}
