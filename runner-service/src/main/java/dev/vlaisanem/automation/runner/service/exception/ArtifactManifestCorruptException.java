package dev.vlaisanem.automation.runner.service.exception;

/**
 * Thrown when a run's {@code manifest.jsonl} cannot be trusted (unparseable/invalid entry, runId
 * mismatch, duplicate artifactId, non-regular-file artifact, or an unterminated trailing line on a
 * terminal run).
 *
 * <p>{@link #getMessage()} is client-safe and sent verbatim by {@code RunExceptionHandler} in a
 * {@code ProblemDetail}; {@link #diagnosticReason()} holds the real cause for server logs only.
 */
public class ArtifactManifestCorruptException extends RuntimeException {

  private final String diagnosticReason;

  public ArtifactManifestCorruptException(String runId, String diagnosticReason) {
    super("Artifact data for run " + runId + " is corrupt and cannot be served.");
    this.diagnosticReason = diagnosticReason;
  }

  public String diagnosticReason() {
    return diagnosticReason;
  }
}
