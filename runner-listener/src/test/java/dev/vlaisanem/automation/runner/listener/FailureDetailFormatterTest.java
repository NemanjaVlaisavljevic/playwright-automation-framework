package dev.vlaisanem.automation.runner.listener;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FailureDetailFormatterTest {

  @Test
  void includesTheExceptionClassAndMessage() {
    String detail = FailureDetailFormatter.format(new IllegalStateException("boom"));

    assertThat(detail).startsWith("java.lang.IllegalStateException: boom");
  }

  @Test
  void fallsBackToJustTheExceptionClassWhenThereIsNoMessage() {
    // The exception is never thrown, so its stack trace is effectively empty/irrelevant here -
    // this only asserts the first line carries no ": <message>" suffix, not the whole string.
    String detail = FailureDetailFormatter.format(new IllegalStateException());

    assertThat(detail.split("\n", 2)[0]).isEqualTo("java.lang.IllegalStateException");
  }

  @Test
  void includesOnlyApplicationStackFrames() {
    Throwable failure = throwFromApplicationCode();

    String detail = FailureDetailFormatter.format(failure);

    assertThat(detail).contains("\tat dev.vlaisanem.automation.runner.listener");
    assertThat(detail).doesNotContain("java.base/");
  }

  @Test
  void limitsApplicationStackFramesToFive() {
    Throwable failure = new RuntimeException("deep");
    StackTraceElement[] deepStack = new StackTraceElement[10];
    for (int i = 0; i < deepStack.length; i++) {
      deepStack[i] =
          new StackTraceElement(
              "dev.vlaisanem.automation.example.Frame" + i, "method", "Frame.java", i);
    }
    failure.setStackTrace(deepStack);

    String detail = FailureDetailFormatter.format(failure);

    assertThat(detail.lines().filter(line -> line.startsWith("\tat")).count()).isEqualTo(5);
  }

  @Test
  void redactsACommonKeyValueSecretInTheMessage() {
    String detail =
        FailureDetailFormatter.format(
            new RuntimeException("request failed, Authorization: Bearer abc123.def456"));

    assertThat(detail).contains("Authorization: ***REDACTED***");
    assertThat(detail).doesNotContain("abc123");
  }

  @Test
  void redactsACookieHeaderValueEntirely() {
    String detail =
        FailureDetailFormatter.format(
            new RuntimeException("request failed, Cookie: SESSION=abc123; Path=/; HttpOnly"));

    assertThat(detail).contains("Cookie: ***REDACTED***");
    assertThat(detail).doesNotContain("abc123");
  }

  @Test
  void redactsASetCookieHeaderValueEntirelyIncludingLaterAttributes() {
    // A real Set-Cookie value contains ';' as part of the value itself (separating Path/HttpOnly/
    // etc from the actual cookie pair), not a boundary to an unrelated field - every attribute
    // after
    // the first ';' must be redacted too, not just the first cookie pair.
    String detail =
        FailureDetailFormatter.format(
            new RuntimeException(
                "response had Set-Cookie: SESSION=abc123; Path=/; HttpOnly; Secure"));

    assertThat(detail).contains("Set-Cookie: ***REDACTED***");
    assertThat(detail).doesNotContain("abc123");
    assertThat(detail).doesNotContain("Path=/");
  }

  @Test
  void redactsAStandaloneBearerTokenWithNoAuthorizationKeyPresent() {
    String detail =
        FailureDetailFormatter.format(
            new RuntimeException("using credential Bearer abc123.def456 for the call"));

    assertThat(detail).contains("Bearer ***REDACTED***");
    assertThat(detail).doesNotContain("abc123");
  }

  @Test
  void redactsAJsonStylePasswordField() {
    String detail =
        FailureDetailFormatter.format(new RuntimeException("body was {\"password\": \"hunter2\"}"));

    assertThat(detail).contains("\"password\": \"***REDACTED***\"");
    assertThat(detail).doesNotContain("hunter2");
  }

  @Test
  void truncatesAnExcessivelyLongDetail() {
    String longMessage = "x".repeat(5000);

    String detail = FailureDetailFormatter.format(new RuntimeException(longMessage));

    assertThat(detail).hasSize(2000);
    assertThat(detail).endsWith("... (truncated)");
  }

  private static Throwable throwFromApplicationCode() {
    try {
      throw new RuntimeException("from application code");
    } catch (RuntimeException caught) {
      return caught;
    }
  }
}
