package dev.vlaisanem.automation.runner.listener;

import java.util.Set;

/**
 * Canonical set of key names treated as sensitive wherever this project redacts captured data,
 * shared by {@link FailureDetailFormatter} and the main suite's {@code JsonSupport#redact} so the
 * two lists never drift apart. Case variants (e.g. {@code apikey}/{@code api-key}) are listed
 * explicitly since {@code JsonSupport} matches by plain lowercase equality, not a pattern.
 */
public final class SensitiveDataKeys {

  public static final Set<String> KEYS =
      Set.of(
          "password",
          "token",
          "authorization",
          "cookie",
          "set-cookie",
          "secret",
          "apikey",
          "api_key",
          "api-key");

  private SensitiveDataKeys() {}
}
