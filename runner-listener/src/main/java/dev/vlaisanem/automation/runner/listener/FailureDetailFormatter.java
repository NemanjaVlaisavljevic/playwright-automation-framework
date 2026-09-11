package dev.vlaisanem.automation.runner.listener;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Formats a failure into a single bounded string for {@code RunnerEvent#detail()}: the exception's
 * class, a redacted message, and up to {@link #MAX_APPLICATION_FRAMES} of this project's own stack
 * frames (never the full trace - too large for an SSE payload and could leak internals). The whole
 * result is truncated to {@link #MAX_LENGTH} characters regardless.
 */
public final class FailureDetailFormatter {

  private static final int MAX_LENGTH = 2000;
  private static final int MAX_APPLICATION_FRAMES = 5;
  private static final String APPLICATION_PACKAGE_PREFIX = "dev.vlaisanem.automation";
  private static final String TRUNCATION_SUFFIX = "... (truncated)";
  private static final String REDACTED = "***REDACTED***";

  /**
   * Simple {@code key: value}/{@code key=value} redaction, not a JSON-aware scanner ({@code
   * JsonSupport#redact()} would discard a whole non-JSON stack trace instead of redacting it). Keys
   * come from {@link SensitiveDataKeys#KEYS}, shared with {@code JsonSupport} so the two never
   * drift apart. Matches to end-of-line/quote, not whitespace or comma/semicolon, since a real
   * Bearer token or Set-Cookie value contains those characters.
   */
  private static final Pattern SENSITIVE_VALUE =
      Pattern.compile(
          "(?i)(" + sensitiveKeyAlternation() + ")([\"']?\\s*[:=]\\s*[\"']?)[^\\r\\n\"']+");

  /**
   * Catches a bearer token with no preceding key (e.g. embedded alone in a log line), which {@link
   * #SENSITIVE_VALUE} would miss. Applied after it, so an already-redacted keyed value leaves no
   * literal {@code Bearer} behind to double-match.
   */
  private static final Pattern STANDALONE_BEARER_TOKEN = Pattern.compile("(?i)\\bBearer\\s+\\S+");

  private FailureDetailFormatter() {}

  public static String format(Throwable throwable) {
    StringBuilder detail = new StringBuilder(throwable.getClass().getName());
    String message = throwable.getMessage();
    if (message != null && !message.isBlank()) {
      detail.append(": ").append(redact(message));
    }
    Arrays.stream(throwable.getStackTrace())
        .filter(frame -> frame.getClassName().startsWith(APPLICATION_PACKAGE_PREFIX))
        .limit(MAX_APPLICATION_FRAMES)
        .forEach(frame -> detail.append("\n\tat ").append(frame));
    String result = detail.toString();
    return result.length() > MAX_LENGTH
        ? result.substring(0, MAX_LENGTH - TRUNCATION_SUFFIX.length()) + TRUNCATION_SUFFIX
        : result;
  }

  private static String redact(String text) {
    String keyValueRedacted = SENSITIVE_VALUE.matcher(text).replaceAll("$1$2" + REDACTED);
    return STANDALONE_BEARER_TOKEN.matcher(keyValueRedacted).replaceAll("Bearer " + REDACTED);
  }

  private static String sensitiveKeyAlternation() {
    return String.join(
        "|", SensitiveDataKeys.KEYS.stream().map(Pattern::quote).toArray(String[]::new));
  }
}
