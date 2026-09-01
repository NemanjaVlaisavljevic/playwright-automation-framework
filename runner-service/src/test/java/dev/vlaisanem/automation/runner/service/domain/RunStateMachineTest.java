package dev.vlaisanem.automation.runner.service.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RunStateMachineTest {

  @Test
  void allowsQueuedToStarting() {
    assertThatCode(() -> RunStateMachine.requireTransition(RunStatus.QUEUED, RunStatus.STARTING))
        .doesNotThrowAnyException();
  }

  @Test
  void allowsCancellingAQueuedRun() {
    assertThatCode(() -> RunStateMachine.requireTransition(RunStatus.QUEUED, RunStatus.CANCELLED))
        .doesNotThrowAnyException();
  }

  @Test
  void allowsRecordingAnInfrastructureErrorBeforeAQueuedRunStarts() {
    assertThatCode(() -> RunStateMachine.requireTransition(RunStatus.QUEUED, RunStatus.ERROR))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @EnumSource(
      value = RunStatus.class,
      names = {"SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "ERROR"})
  void allowsEveryTerminalOutcomeFromRunning(RunStatus terminal) {
    assertThatCode(() -> RunStateMachine.requireTransition(RunStatus.RUNNING, terminal))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @EnumSource(RunStatus.class)
  void rejectsAnyTransitionOutOfATerminalStatus(RunStatus terminal) {
    assumeTrue(terminal.isTerminal());

    assertThatThrownBy(() -> RunStateMachine.requireTransition(terminal, RunStatus.RUNNING))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectsSkippingStraightFromQueuedToRunning() {
    assertThatThrownBy(() -> RunStateMachine.requireTransition(RunStatus.QUEUED, RunStatus.RUNNING))
        .isInstanceOf(IllegalStateException.class);
  }
}
