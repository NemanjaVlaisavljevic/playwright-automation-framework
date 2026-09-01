package dev.vlaisanem.automation.runner.service.exception;

import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Suite;

/**
 * Thrown when a requested (environment, suite) combination is not on the allowlist. HTTP status
 * mapping lives in {@code RunExceptionHandler} (a {@code @RestControllerAdvice}), not here - the
 * service layer should not need to know it is being called over HTTP.
 */
public class UnsupportedRunCombinationException extends RuntimeException {

  public UnsupportedRunCombinationException(Environment environment, Suite suite) {
    super("Unsupported environment/suite combination: " + environment + "/" + suite);
  }
}
