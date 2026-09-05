package dev.vlaisanem.automation.runner.service.api;

import dev.vlaisanem.automation.runner.service.catalog.TestCatalogUnavailableException;
import dev.vlaisanem.automation.runner.service.catalog.UnsupportedTestCatalogEnvironmentException;
import dev.vlaisanem.automation.runner.service.exception.ArtifactManifestCorruptException;
import dev.vlaisanem.automation.runner.service.exception.ArtifactNotFoundException;
import dev.vlaisanem.automation.runner.service.exception.InvalidEventResumeSequenceException;
import dev.vlaisanem.automation.runner.service.exception.RunEventPersistenceException;
import dev.vlaisanem.automation.runner.service.exception.RunEventSubscriptionRejectedException;
import dev.vlaisanem.automation.runner.service.exception.RunLogNotFoundException;
import dev.vlaisanem.automation.runner.service.exception.RunNotFoundException;
import dev.vlaisanem.automation.runner.service.exception.RunQueueFullException;
import dev.vlaisanem.automation.runner.service.exception.RunnerDegradedException;
import dev.vlaisanem.automation.runner.service.exception.UnsupportedRunCombinationException;
import dev.vlaisanem.automation.runner.service.orchestration.InvalidTestSelectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralizes HTTP status mapping for the service layer's exceptions here, so domain/service code
 * never needs a Spring MVC annotation (e.g. no {@code @ResponseStatus} on a service exception).
 *
 * <p>{@link #handleUnexpected} is the last-resort catch-all: Spring dispatches to the most specific
 * matching {@code @ExceptionHandler}, so it can never intercept anything the handlers above already
 * cover - it only ever sees a genuinely unmapped failure (a bug, or a dependency throwing something
 * this service has never seen before). Its response is deliberately generic and never echoes {@link
 * Throwable#getMessage()}: an internal exception message can carry paths, stack internals, or other
 * detail that was never meant to reach a client, whereas every other handler here re-exposes {@code
 * getMessage()} deliberately, because those exceptions' messages are authored specifically to be
 * client-safe (see each one's own Javadoc). The original exception is still logged in full server
 * side, so nothing is lost for diagnosis.
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

  @ExceptionHandler(RunEventSubscriptionRejectedException.class)
  public ProblemDetail handleSubscriptionRejected(RunEventSubscriptionRejectedException exception) {
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
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
   * A missing/unreadable/invalid catalog file is a deployment problem, not a client error - 503
   * (not 400) is the honest status: retrying the exact same request later, once the file is fixed,
   * would succeed. Like {@link ArtifactManifestCorruptException}, {@link
   * TestCatalogUnavailableException#getMessage()} is client-safe by construction, never by
   * coincidence - the real cause ({@link TestCatalogUnavailableException#diagnosticReason()}, which
   * can legitimately contain a resolved absolute filesystem path) is logged here and never sent to
   * the client.
   */
  @ExceptionHandler(TestCatalogUnavailableException.class)
  public ProblemDetail handleTestCatalogUnavailable(TestCatalogUnavailableException exception) {
    log.error("Test catalog unavailable: {}", exception.diagnosticReason(), exception);
    return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
  }

  /**
   * Deliberately its own handler, not left to fall through to {@link #handleUnexpected} - a corrupt
   * manifest is a real, distinguishable data-integrity problem, so the client gets a specific
   * (though still generic) detail message identifying which run, rather than the catch-all's fully
   * generic one. Unlike every other handler above, {@link
   * ArtifactManifestCorruptException#getMessage()} is <em>not</em> re-exposed because it happens to
   * be client-safe by coincidence - it is client-safe by construction (see the exception's own
   * Javadoc); the real cause ({@link ArtifactManifestCorruptException#diagnosticReason()}, which
   * can legitimately contain an absolute filesystem path or a raw Jackson error) is logged here and
   * never sent to the client.
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
