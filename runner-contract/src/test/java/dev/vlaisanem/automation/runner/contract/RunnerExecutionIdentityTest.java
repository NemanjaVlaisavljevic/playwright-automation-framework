package dev.vlaisanem.automation.runner.contract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RunnerExecutionIdentityTest {

  @Test
  void prefersTheConfiguredPropertyOverEverythingElse() {
    assertThat(RunnerExecutionIdentity.resolve(" configured-id ", "env-id"))
        .isEqualTo("configured-id");
  }

  @Test
  void fallsBackToTheEnvironmentVariableWhenThePropertyIsAbsentOrBlank() {
    assertThat(RunnerExecutionIdentity.resolve(null, " env-id ")).isEqualTo("env-id");
    assertThat(RunnerExecutionIdentity.resolve(" ", "env-id")).isEqualTo("env-id");
  }

  @Test
  void generatesAFreshLocalFallbackWhenNeitherIsSet() {
    String first = RunnerExecutionIdentity.resolve(null, null);
    String second = RunnerExecutionIdentity.resolve(null, null);

    assertThat(first).startsWith("local-");
    assertThat(second).startsWith("local-");
    // Each call to the pure resolver mints its own fresh UUID - the "resolved once per JVM"
    // guarantee comes from currentRunId() caching this in a static field, not from resolve()
    // itself.
    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void currentRunIdIsStableAcrossRepeatedCallsWithinThisJvm() {
    assertThat(RunnerExecutionIdentity.currentRunId())
        .isEqualTo(RunnerExecutionIdentity.currentRunId());
  }
}
