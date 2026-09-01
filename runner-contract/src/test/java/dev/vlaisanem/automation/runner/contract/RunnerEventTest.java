package dev.vlaisanem.automation.runner.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RunnerEventTest {

  private static final Instant NOW = Instant.parse("2026-08-29T12:00:00Z");

  @Test
  void runLevelEventAcceptsNoTestIdentifier() {
    RunnerEvent event =
        new RunnerEvent(
            RunnerEvent.CURRENT_SCHEMA_VERSION,
            "run-1",
            1,
            NOW,
            EventType.RUN_STARTED,
            null,
            null,
            null,
            null);

    assertThat(event.testId()).isNull();
    assertThat(event.testDisplayName()).isNull();
  }

  @Test
  void runLevelEventRejectsTestIdentifier() {
    assertThatThrownBy(
            () ->
                new RunnerEvent(
                    RunnerEvent.CURRENT_SCHEMA_VERSION,
                    "run-1",
                    1,
                    NOW,
                    EventType.RUN_FINISHED,
                    RunOutcome.SUCCEEDED,
                    "some-test-id",
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not carry a test identifier");
  }

  @Test
  void testLevelEventRequiresTestIdentifier() {
    assertThatThrownBy(
            () ->
                new RunnerEvent(
                    RunnerEvent.CURRENT_SCHEMA_VERSION,
                    "run-1",
                    2,
                    NOW,
                    EventType.TEST_STARTED,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires a non-blank testId");
  }

  @Test
  void testLevelEventRequiresDisplayName() {
    assertThatThrownBy(
            () ->
                new RunnerEvent(
                    RunnerEvent.CURRENT_SCHEMA_VERSION,
                    "run-1",
                    2,
                    NOW,
                    EventType.TEST_STARTED,
                    null,
                    "test-id",
                    " ",
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires a non-blank testDisplayName");
  }

  @Test
  void testLevelEventRejectsNullDisplayName() {
    assertThatThrownBy(
            () ->
                new RunnerEvent(
                    RunnerEvent.CURRENT_SCHEMA_VERSION,
                    "run-1",
                    2,
                    NOW,
                    EventType.TEST_STARTED,
                    null,
                    "test-id",
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires a non-blank testDisplayName");
  }

  @Test
  void runFinishedRequiresOutcome() {
    assertThatThrownBy(
            () ->
                new RunnerEvent(
                    RunnerEvent.CURRENT_SCHEMA_VERSION,
                    "run-1",
                    2,
                    NOW,
                    EventType.RUN_FINISHED,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("RUN_FINISHED requires a runOutcome");
  }

  @Test
  void nonTerminalEventRejectsOutcome() {
    assertThatThrownBy(
            () ->
                new RunnerEvent(
                    RunnerEvent.CURRENT_SCHEMA_VERSION,
                    "run-1",
                    1,
                    NOW,
                    EventType.RUN_STARTED,
                    RunOutcome.SUCCEEDED,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not carry a runOutcome");
  }

  @Test
  void rejectsBlankRunId() {
    assertThatThrownBy(
            () ->
                new RunnerEvent(
                    RunnerEvent.CURRENT_SCHEMA_VERSION,
                    " ",
                    1,
                    NOW,
                    EventType.RUN_STARTED,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("runId must not be blank");
  }

  @Test
  void runQueuedCarriesNoOutcomeOrTestIdentifier() {
    RunnerEvent event = RunnerEvent.runQueued("run-1", 1, NOW);

    assertThat(event.type()).isEqualTo(EventType.RUN_QUEUED);
    assertThat(event.runOutcome()).isNull();
    assertThat(event.testId()).isNull();
    assertThat(event.testDisplayName()).isNull();
  }

  @Test
  void runStartedCarriesNoOutcomeOrTestIdentifier() {
    RunnerEvent event = RunnerEvent.runStarted("run-1", 2, NOW);

    assertThat(event.type()).isEqualTo(EventType.RUN_STARTED);
    assertThat(event.runOutcome()).isNull();
    assertThat(event.testId()).isNull();
    assertThat(event.testDisplayName()).isNull();
  }

  @Test
  void runFinishedCarriesTheGivenOutcomeAndOptionalDetail() {
    RunnerEvent event = RunnerEvent.runFinished("run-1", 3, NOW, RunOutcome.FAILED, "exit code 1");

    assertThat(event.type()).isEqualTo(EventType.RUN_FINISHED);
    assertThat(event.runOutcome()).isEqualTo(RunOutcome.FAILED);
    assertThat(event.detail()).isEqualTo("exit code 1");
    assertThat(event.testId()).isNull();
  }

  @Test
  void rejectsNonPositiveSequence() {
    assertThatThrownBy(
            () ->
                new RunnerEvent(
                    RunnerEvent.CURRENT_SCHEMA_VERSION,
                    "run-1",
                    0,
                    NOW,
                    EventType.RUN_STARTED,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence must be positive");
  }
}
