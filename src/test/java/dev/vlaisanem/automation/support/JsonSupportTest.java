package dev.vlaisanem.automation.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JsonSupportTest {

  @Test
  void redactsAKnownSensitiveFieldRegardlessOfCase() {
    String redacted = JsonSupport.redact("{\"Password\": \"hunter2\"}");

    assertThat(redacted).contains("REDACTED");
    assertThat(redacted).doesNotContain("hunter2");
  }

  /** Verifies the shared {@code SensitiveDataKeys} set is used directly, not duplicated here. */
  @Test
  void redactsACookieField() {
    String redacted = JsonSupport.redact("{\"cookie\": \"SESSION=abc123\"}");

    assertThat(redacted).contains("REDACTED");
    assertThat(redacted).doesNotContain("abc123");
  }

  @Test
  void leavesNonSensitiveFieldsUntouched() {
    String redacted = JsonSupport.redact("{\"username\": \"alice\"}");

    assertThat(redacted).contains("alice");
  }
}
