package dev.vlaisanem.automation.runner.service.catalog;

import java.nio.file.Path;

/**
 * The committed test catalog file is missing, unreadable, or fails content validation - a
 * deployment/build problem (the file ships with the repository, {@code testCatalogCheck} guards it
 * in CI at generation time, and {@link TestCatalogContentValidator} guards it again at runtime-load
 * time), never something a client request caused. HTTP status mapping lives in {@code
 * RunExceptionHandler}.
 *
 * <p>{@link #getMessage()} is deliberately generic and client-safe - it is what {@code
 * RunExceptionHandler} sends verbatim in a {@code ProblemDetail}, so it never echoes the resolved
 * absolute filesystem path or a raw Jackson/validation error. {@link #diagnosticReason()} carries
 * that real detail (which can legitimately include an absolute filesystem path) and is meant for
 * the server-side log only - see the handler's own logging.
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
