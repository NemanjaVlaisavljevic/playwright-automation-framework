package dev.vlaisanem.automation.runner.service.exception;

/**
 * Thrown when a run's {@code manifest.jsonl} cannot be trusted: a syntactically complete line that
 * fails to parse or validate, an entry whose {@code runId} does not match the run being served, a
 * duplicate {@code artifactId}, an artifact resolved to something other than a regular file, or
 * (only once the run has reached a terminal status - see {@code ArtifactManifestReader}) an
 * unterminated trailing line. Deliberately its own exception type, not folded into a generic 500,
 * so this is reported as a distinguishable data-integrity problem rather than an opaque internal
 * error.
 *
 * <p>{@link #getMessage()} is deliberately generic and client-safe - it is what {@code
 * RunExceptionHandler} sends verbatim in a {@code ProblemDetail}. {@link #diagnosticReason()} is
 * the actual cause (which can legitimately include an absolute filesystem path or a raw Jackson
 * error message) and is meant for the server-side log only - see the handler's own logging.
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
