package dev.vlaisanem.automation.runner.service.api;

import dev.vlaisanem.automation.runner.service.catalog.TestCatalogUnavailableException;
import dev.vlaisanem.automation.runner.service.catalog.UnsupportedTestCatalogEnvironmentException;
import dev.vlaisanem.automation.runner.service.exception.ArtifactManifestCorruptException;
import dev.vlaisanem.automation.runner.service.exception.ArtifactNotFoundException;
import dev.vlaisanem.automation.runner.service.exception.DiskSpaceLowException;
import dev.vlaisanem.automation.runner.service.exception.DiskUsageUnavailableException;
import dev.vlaisanem.automation.runner.service.exception.InvalidEventResumeSequenceException;
import dev.vlaisanem.automation.runner.service.exception.RunEventPersistenceException;
import dev.vlaisanem.automation.runner.service.exception.RunEventSubscriptionRejectedException;
import dev.vlaisanem.automation.runner.service.exception.RunLogNotFoundException;
import dev.vlaisanem.automation.runner.service.exception.RunNotFoundException;
import dev.vlaisanem.automation.runner.service.exception.RunQueueFullException;
import dev.vlaisanem.automation.runner.service.exception.RunnerDegradedException;
import dev.vlaisanem.automation.runner.service.exception.RunnerRecoveringException;
import dev.vlaisanem.automation.runner.service.exception.SseConnectionLimitExceededException;
import dev.vlaisanem.automation.runner.service.exception.UnsupportedRunCombinationException;
import dev.vlaisanem.automation.runner.service.orchestration.InvalidTestSelectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralizes HTTP status mapping for the service layer's exceptions, so domain/service code never
 * needs a Spring MVC annotation.
 *
 * <p>{@link #handleUnexpected} is the last-resort catch-all and never echoes {@link
 * Throwable#getMessage()} to the client - an internal exception message can carry paths or stack
 * internals. Every other handler here re-exposes {@code getMessage()} deliberately, because those
 * messages are authored to be client-safe.
 */
@RestControllerAdvice
public class RunExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(RunExceptionHandler.class);

  @ExceptionHandler(UnsupportedRunCombinationException.class)
  public ProblemDetail handleUnsupportedCombination(UnsupportedRunCombinationException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
  }

  @ExceptionHandler(RunNotFoundException.class)
  public ProblemDetail handleNotFound(RunNotFoundException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
  }

  @ExceptionHandler(RunLogNotFoundException.class)
  public ProblemDetail handleLogNotFound(RunLogNotFoundException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
  }

  @ExceptionHandler(RunQueueFullException.class)
  public ProblemDetail handleQueueFull(RunQueueFullException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
  }

  @ExceptionHandler(RunEventPersistenceException.class)
  public ProblemDetail handleEventPersistence(RunEventPersistenceException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
  }

  @ExceptionHandler(RunnerDegradedException.class)
  public ProblemDetail handleDegraded(RunnerDegradedException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
  }

  @ExceptionHandler(RunnerRecoveringException.class)
  public ProblemDetail handleRecovering(RunnerRecoveringException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
  }

  @ExceptionHandler(DiskSpaceLowException.class)
  public ProblemDetail handleDiskSpaceLow(DiskSpaceLowException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
  }

  /**
   * The disk-usage probe itself failing (not merely reporting low space) is its own distinct 503: a
   * fail-closed guard that can't answer its own question must refuse work, not fall through to a
   * generic 500.
   */
  @ExceptionHandler(DiskUsageUnavailableException.class)
  public ProblemDetail handleDiskUsageUnavailable(DiskUsageUnavailableException exception) {
    log.error("Disk usage probe failed: {}", exception.getMessage(), exception);
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
  }

  @ExceptionHandler(RunEventSubscriptionRejectedException.class)
  public ProblemDetail handleSubscriptionRejected(RunEventSubscriptionRejectedException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
  }

  /**
   * {@code 429}, not {@code 503}, distinct from {@link #handleSubscriptionRejected}. A
   * concurrent-connection cap has no fixed reopening time, so {@code Retry-After} here is a
   * suggested backoff, not a guaranteed one.
   */
  @ExceptionHandler(SseConnectionLimitExceededException.class)
  public ResponseEntity<ProblemDetail> handleSseConnectionLimitExceeded(
      SseConnectionLimitExceededException exception) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage());
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, "5")
        .body(problem);
  }

  @ExceptionHandler(InvalidEventResumeSequenceException.class)
  public ProblemDetail handleInvalidResumeSequence(InvalidEventResumeSequenceException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
  }

  @ExceptionHandler(ArtifactNotFoundException.class)
  public ProblemDetail handleArtifactNotFound(ArtifactNotFoundException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
  }

  @ExceptionHandler(InvalidTestSelectionException.class)
  public ProblemDetail handleInvalidTestSelection(InvalidTestSelectionException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
  }

  @ExceptionHandler(UnsupportedTestCatalogEnvironmentException.class)
  public ProblemDetail handleUnsupportedTestCatalogEnvironment(
      UnsupportedTestCatalogEnvironmentException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
  }

  /**
   * A missing/unreadable/invalid catalog file is a deployment problem, not a client error - 503,
   * not 400: retrying later, once the file is fixed, would succeed. {@link
   * TestCatalogUnavailableException#diagnosticReason()} can contain an absolute filesystem path and
   * is logged here, never sent to the client.
   */
  @ExceptionHandler(TestCatalogUnavailableException.class)
  public ProblemDetail handleTestCatalogUnavailable(TestCatalogUnavailableException exception) {
    log.error("Test catalog unavailable: {}", exception.diagnosticReason(), exception);
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
  }

  /**
   * Its own handler rather than falling through to {@link #handleUnexpected}, so the client gets a
   * specific detail message identifying which run. {@link
   * ArtifactManifestCorruptException#diagnosticReason()} can contain an absolute filesystem path or
   * a raw Jackson error and is logged here, never sent to the client.
   */
  @ExceptionHandler(ArtifactManifestCorruptException.class)
  public ProblemDetail handleArtifactManifestCorrupt(ArtifactManifestCorruptException exception) {
    log.error("Artifact manifest corruption detected: {}", exception.diagnosticReason(), exception);
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleUnexpected(Exception exception) {
    log.error("Unexpected error while handling a runner-service request", exception);
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected internal error occurred.");
  }
}
