package dev.vlaisanem.automation.runner.service.domain;

/**
 * The automation suite's own layer taxonomy (exactly one per test, enforced by {@code
 * AutomationExtension}) - mirrored here, not shared as a Java type, since the main suite and this
 * service are independent Gradle modules with no dependency between them. {@code smoke} is
 * deliberately not a fourth value here: it is a cross-cutting tag a test can carry alongside any
 * layer, not a layer itself.
 */
public enum TestLayer {
  API,
  UI,
  JOURNEY
}
