package dev.vlaisanem.automation.runner.listener;

import java.util.Set;

/**
 * The canonical set of key names treated as sensitive everywhere this project redacts captured data
 * before it reaches a manifest, an event, or a log - shared by {@link FailureDetailFormatter}
 * (arbitrary failure text) and the main suite's {@code JsonSupport#redact} (JSON response bodies),
 * so the two lists can never independently drift apart and miss a key the other one already knows
 * about.
 *
 * <p>Every case variant a real header/field name could use is listed explicitly (e.g. both {@code
 * apikey} and {@code api-key}) rather than relying on a regex character class, since {@code
 * JsonSupport} matches a field name by plain lowercase equality, not a pattern.
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
