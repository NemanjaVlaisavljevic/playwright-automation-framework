package dev.vlaisanem.automation.runner.service.catalog;

import java.nio.file.Path;

/**
 * The committed test catalog file is missing, unreadable, or fails content validation - a
 * deployment/build problem, never something a client request caused. HTTP status mapping lives in
 * {@code RunExceptionHandler}.
 *
 * <p>{@link #getMessage()} is generic and client-safe, sent verbatim in a {@code ProblemDetail}.
 * {@link #diagnosticReason()} carries the real detail (which can include an absolute filesystem
 * path) and is meant for the server-side log only.
 */
public class TestCatalogUnavailableException extends RuntimeException {

  private static final String CLIENT_MESSAGE = "Test catalog is unavailable.";

  private final String diagnosticReason;

  public TestCatalogUnavailableException(Path catalogFile) {
    super(CLIENT_MESSAGE);
    this.diagnosticReason = "Test catalog file not found: " + catalogFile;
  }

  public TestCatalogUnavailableException(Path catalogFile, Throwable cause) {
    super(CLIENT_MESSAGE, cause);
    this.diagnosticReason =
        "Test catalog file could not be loaded: " + catalogFile + " (" + cause.getMessage() + ")";
  }

  public String diagnosticReason() {
    return diagnosticReason;
  }
}
