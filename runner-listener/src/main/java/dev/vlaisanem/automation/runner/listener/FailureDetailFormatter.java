package dev.vlaisanem.automation.runner.listener;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Formats a failure into a single bounded string for {@code RunnerEvent#detail()} - shared by
 * {@link RunnerEventTestExecutionListener} ({@code TEST_FAILED}/{@code TEST_ABORTED}) and the main
 * automation suite's {@code Steps} API ({@code STEP_FAILED}), so every failure reaching the
 * dashboard carries the same shape: the exception's class, a redacted message, and a handful of the
 * application's own stack frames - never the full trace, which would be excessive for an SSE
 * payload every viewer receives and could itself contain sensitive framework/library internals.
 *
 * <p>Bounded twice over: at most {@link #MAX_APPLICATION_FRAMES} stack frames (only frames inside
 * this project's own {@code dev.vlaisanem.automation} package - JDK/library/Playwright internals
 * add noise, not the "where did my test/step fail" signal a drill-down needs), and the whole result
 * truncated to {@link #MAX_LENGTH} characters regardless.
 */
public final class FailureDetailFormatter {

  private static final int MAX_LENGTH = 2000;
  private static final int MAX_APPLICATION_FRAMES = 5;
  private static final String APPLICATION_PACKAGE_PREFIX = "dev.vlaisanem.automation";
  private static final String TRUNCATION_SUFFIX = "... (truncated)";
  private static final String REDACTED = "***REDACTED***";

  /**
   * Deliberately simple {@code key: value} / {@code key=value} redaction, not a general-purpose
   * secret scanner - catches the common cases (an HTTP header line, a query string, a JSON-ish
   * {@code "password": "..."} fragment inside a caught response body) without trying to be
   * exhaustive. {@code JsonSupport#redact()} only understands well-formed JSON and would discard an
   * entire non-JSON stack trace as {@code "<non-JSON body omitted>"}, losing everything - useless
   * for arbitrary exception text. The key alternation is built from {@link SensitiveDataKeys#KEYS},
   * the same set {@code JsonSupport} matches JSON field names against, so the two can never
   * independently drift apart.
   *
   * <p>The value is matched up to end-of-line/quote, not just to the next whitespace or comma/
   * semicolon: a real {@code Authorization: Bearer abc123.def456} value contains a space (stopping
   * at whitespace would redact only the literal word "Bearer" and leak the actual token after it),
   * and a real {@code Set-Cookie: SESSION=abc; Path=/; HttpOnly} value contains semicolons as part
   * of the value itself, not a boundary to some unrelated field (stopping there would leave every
   * cookie pair after the first one leaked in plain text).
   */
  private static final Pattern SENSITIVE_VALUE =
      Pattern.compile(
          "(?i)(" + sensitiveKeyAlternation() + ")([\"']?\\s*[:=]\\s*[\"']?)[^\\r\\n\"']+");

  /**
   * A bearer token can appear with no {@code Authorization:} key at all (e.g. copied into a log
   * message, or a caught response body that just embeds the header value alone) - {@link
   * #SENSITIVE_VALUE} only fires when one of {@link SensitiveDataKeys#KEYS} precedes it, so this is
   * a separate, unconditional pass over the word {@code Bearer} itself. Applied after {@link
   * #SENSITIVE_VALUE}, so a {@code "authorization"}-keyed value redacted already contains no
   * literal {@code Bearer} left for this pass to find.
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
