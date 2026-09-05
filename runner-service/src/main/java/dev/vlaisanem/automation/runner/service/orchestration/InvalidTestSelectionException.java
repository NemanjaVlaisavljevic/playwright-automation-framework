package dev.vlaisanem.automation.runner.service.orchestration;

/**
 * A {@code CUSTOM} run request's {@code testKeys} failed validation - see {@link
 * CustomTestSelectionValidator} for every rule this can mean. HTTP status mapping lives in {@code
 * RunExceptionHandler}.
 */
public class InvalidTestSelectionException extends RuntimeException {

  public InvalidTestSelectionException(String message) {
    super(message);
  }
}
