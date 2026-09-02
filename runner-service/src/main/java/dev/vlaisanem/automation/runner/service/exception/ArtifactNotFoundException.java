package dev.vlaisanem.automation.runner.service.exception;

/** Thrown when a run exists but no artifact with the given {@code artifactId} does. */
public class ArtifactNotFoundException extends RuntimeException {

  public ArtifactNotFoundException(String runId, String artifactId) {
    super("No artifact " + artifactId + " found for run " + runId);
  }
}
