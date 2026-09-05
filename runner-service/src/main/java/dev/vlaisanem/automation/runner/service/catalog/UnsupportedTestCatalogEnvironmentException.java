package dev.vlaisanem.automation.runner.service.catalog;

import dev.vlaisanem.automation.runner.service.domain.Environment;

/**
 * {@code GET /api/v1/tests} was asked for an environment other than {@code PUBLIC} - {@code CUSTOM}
 * (the only suite this catalog exists for) has no {@code LOCAL} mapping in {@code RunCatalog}. HTTP
 * status mapping lives in {@code RunExceptionHandler}.
 */
public class UnsupportedTestCatalogEnvironmentException extends RuntimeException {

  public UnsupportedTestCatalogEnvironmentException(Environment environment) {
    super("CUSTOM test selection only exists for PUBLIC, got " + environment);
  }
}
