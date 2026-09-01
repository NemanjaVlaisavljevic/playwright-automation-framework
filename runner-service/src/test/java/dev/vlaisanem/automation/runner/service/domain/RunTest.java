package dev.vlaisanem.automation.runner.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RunTest {

  private static final Instant T0 = Instant.parse("2026-08-29T12:00:00Z");

  @Test
  void queuedFactoryStartsWithNoTimestampsOrResult() {
    Run run = Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, T0);

    assertThat(run.status()).isEqualTo(RunStatus.QUEUED);
    assertThat(run.requestedAt()).isEqualTo(T0);
    assertThat(run.startedAt()).isNull();
    assertThat(run.finishedAt()).isNull();
    assertThat(run.exitCode()).isNull();
  }

  @Test
  void transitionToRunningSetsStartedAt() {
    Run run =
        Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, T0)
            .transitionTo(RunStatus.STARTING, T0.plusSeconds(1))
            .transitionTo(RunStatus.RUNNING, T0.plusSeconds(2));

    assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
    assertThat(run.startedAt()).isEqualTo(T0.plusSeconds(2));
    assertThat(run.finishedAt()).isNull();
  }

  @Test
  void terminalTransitionSetsFinishedAtAndResult() {
    Run run =
        Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, T0)
            .transitionTo(RunStatus.STARTING, T0.plusSeconds(1))
            .transitionTo(RunStatus.RUNNING, T0.plusSeconds(2))
            .transitionTo(RunStatus.FAILED, T0.plusSeconds(3), 1, "2 tests failed");

    assertThat(run.status()).isEqualTo(RunStatus.FAILED);
    assertThat(run.finishedAt()).isEqualTo(T0.plusSeconds(3));
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.detail()).isEqualTo("2 tests failed");
  }

  @Test
  void rejectsAnInvalidTransition() {
    Run run = Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, T0);

    assertThatThrownBy(() -> run.transitionTo(RunStatus.SUCCEEDED, T0.plusSeconds(1)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectsBlankRunId() {
    assertThatThrownBy(() -> Run.queued(" ", Environment.PUBLIC, Suite.SMOKE, T0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("runId must not be blank");
  }

  @Test
  void runningRequiresStartedAt() {
    assertThatThrownBy(
            () ->
                new Run(
                    "run-1",
                    Environment.PUBLIC,
                    Suite.SMOKE,
                    RunStatus.RUNNING,
                    T0,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RUNNING requires startedAt");
  }

  @Test
  void queuedMustNotCarryStartedAt() {
    assertThatThrownBy(
            () ->
                new Run(
                    "run-1",
                    Environment.PUBLIC,
                    Suite.SMOKE,
                    RunStatus.QUEUED,
                    T0,
                    T0,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not carry startedAt");
  }

  @Test
  void terminalRequiresFinishedAt() {
    assertThatThrownBy(
            () ->
                new Run(
                    "run-1",
                    Environment.PUBLIC,
                    Suite.SMOKE,
                    RunStatus.SUCCEEDED,
                    T0,
                    T0,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires finishedAt");
  }

  @Test
  void nonTerminalMustNotCarryFinishedAt() {
    assertThatThrownBy(
            () ->
                new Run(
                    "run-1",
                    Environment.PUBLIC,
                    Suite.SMOKE,
                    RunStatus.QUEUED,
                    T0,
                    null,
                    T0,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not carry finishedAt");
  }

  @Test
  void nonTerminalMustNotCarryAResult() {
    assertThatThrownBy(
            () ->
                new Run(
                    "run-1",
                    Environment.PUBLIC,
                    Suite.SMOKE,
                    RunStatus.RUNNING,
                    T0,
                    T0,
                    null,
                    0,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not carry a result");
  }

  @Test
  void successfulRunRequiresStartedAt() {
    assertThatThrownBy(
            () ->
                new Run(
                    "run-1",
                    Environment.PUBLIC,
                    Suite.SMOKE,
                    RunStatus.SUCCEEDED,
                    T0,
                    null,
                    T0.plusSeconds(2),
                    0,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SUCCEEDED requires startedAt");
  }

  @Test
  void startedAtMustNotBeBeforeRequestedAt() {
    assertThatThrownBy(
            () ->
                new Run(
                    "run-1",
                    Environment.PUBLIC,
                    Suite.SMOKE,
                    RunStatus.RUNNING,
                    T0,
                    T0.minusSeconds(1),
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("startedAt must not be before requestedAt");
  }

  @Test
  void finishedAtMustNotBeBeforeStartedAt() {
    assertThatThrownBy(
            () ->
                new Run(
                    "run-1",
                    Environment.PUBLIC,
                    Suite.SMOKE,
                    RunStatus.FAILED,
                    T0,
                    T0.plusSeconds(2),
                    T0.plusSeconds(1),
                    1,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("finishedAt must not be before startedAt");
  }

  @Test
  void transitionRequiresANonNullTimestamp() {
    Run queued = Run.queued("run-1", Environment.PUBLIC, Suite.SMOKE, T0);

    assertThatThrownBy(() -> queued.transitionTo(RunStatus.STARTING, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("now must not be null");
  }
}
