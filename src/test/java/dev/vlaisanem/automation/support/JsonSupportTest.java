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

  /**
   * Proves {@code SensitiveDataKeys} is actually wired in here, not just referenced - a field this
   * class didn't know about before (see the review that added {@code cookie}/{@code set-cookie} to
   * the shared set) must be redacted without any change to this class itself.
   */
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
