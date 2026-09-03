package dev.vlaisanem.automation.runner.listener;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunOutcome;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RunnerEventObjectMapperTest {

  private final ObjectMapper objectMapper = RunnerEventObjectMapper.create();

  @Test
  void roundTripsRunnerEventWithIsoTimestampAndOutcome() throws Exception {
    RunnerEvent expected =
        new RunnerEvent(
            RunnerEvent.CURRENT_SCHEMA_VERSION,
            "run-1",
            7,
            Instant.parse("2026-08-29T12:00:00Z"),
            EventType.RUN_FINISHED,
            RunOutcome.SUCCEEDED,
            null,
            null,
            null,
            null,
            null);

    String json = objectMapper.writeValueAsString(expected);
    RunnerEvent actual = objectMapper.readValue(json, RunnerEvent.class);

    assertThat(json).contains("\"timestamp\":\"2026-08-29T12:00:00Z\"");
    assertThat(json).doesNotContain("testId", "testDisplayName", "detail");
    assertThat(actual).isEqualTo(expected);
  }

  @Test
  void roundTripsTestLevelEventWithoutRunOutcome() throws Exception {
    RunnerEvent expected =
        RunnerEvent.testFailed(
            "run-1",
            3,
            Instant.parse("2026-08-29T12:00:05Z"),
            "[engine:junit-jupiter]/[class:Fixture]/[method:failing()]",
            "failing()",
            "boom");

    String json = objectMapper.writeValueAsString(expected);
    RunnerEvent actual = objectMapper.readValue(json, RunnerEvent.class);

    assertThat(json).doesNotContain("runOutcome");
    assertThat(actual).isEqualTo(expected);
  }
}
