package dev.vlaisanem.automation.runner.service.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.events.RunEventBroker;
import dev.vlaisanem.automation.runner.service.events.RunEventSubscriber;
import dev.vlaisanem.automation.runner.service.exception.RunEventSubscriptionRejectedException;
import dev.vlaisanem.automation.runner.service.exception.RunNotFoundException;
import dev.vlaisanem.automation.runner.service.orchestration.RunService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-contract tests for {@link RunEventStreamController}: status-code mapping and {@code
 * Last-Event-ID} parsing. The cleanup/atomicity guarantees the review asked for are proven
 * separately and deterministically in {@link DeferredSubscriptionHandleTest} and {@link
 * EmitterGuardTest} - Spring's {@code ResponseBodyEmitter.Handler} is package-private, so it cannot
 * be driven from a test in this package the way a live client connection would.
 */
@WebMvcTest(controllers = {RunEventStreamController.class, RunExceptionHandler.class})
@EnableConfigurationProperties(RunnerProperties.class)
class RunEventStreamControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private RunService runService;

  @MockitoBean private RunEventBroker broker;

  @Test
  void streamReturns404ForAnUnknownRunId() throws Exception {
    when(runService.find("missing")).thenThrow(new RunNotFoundException("missing"));

    mockMvc.perform(get("/api/v1/runs/missing/events")).andExpect(status().isNotFound());
  }

  @Test
  void streamReturns503WhenTheSubscriberCapacityIsExceeded() throws Exception {
    Run run =
        Run.queued("run-1", Environment.PUBLIC, Suite.API, Instant.parse("2026-08-31T00:00:00Z"));
    when(runService.find("run-1")).thenReturn(run);
    when(broker.replayAndSubscribe(eq("run-1"), anyLong(), any()))
        .thenThrow(
            new RunEventSubscriptionRejectedException(
                "Maximum of 100 concurrent event subscribers reached"));

    mockMvc.perform(get("/api/v1/runs/run-1/events")).andExpect(status().isServiceUnavailable());
  }

  @Test
  void streamReturns400ForAMalformedLastEventIdHeader() throws Exception {
    Run run =
        Run.queued("run-1", Environment.PUBLIC, Suite.API, Instant.parse("2026-08-31T00:00:00Z"));
    when(runService.find("run-1")).thenReturn(run);

    mockMvc
        .perform(get("/api/v1/runs/run-1/events").header("Last-Event-ID", "not-a-number"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void streamReturns400ForANegativeLastEventId() throws Exception {
    Run run =
        Run.queued("run-1", Environment.PUBLIC, Suite.API, Instant.parse("2026-08-31T00:00:00Z"));
    when(runService.find("run-1")).thenReturn(run);

    mockMvc
        .perform(get("/api/v1/runs/run-1/events").header("Last-Event-ID", "-1"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void streamWithNoLastEventIdReplaysTheFullHistory() throws Exception {
    Run run =
        Run.queued("run-1", Environment.PUBLIC, Suite.API, Instant.parse("2026-08-31T00:00:00Z"));
    when(runService.find("run-1")).thenReturn(run);

    mockMvc.perform(get("/api/v1/runs/run-1/events")).andExpect(request().asyncStarted());

    verify(broker).replayAndSubscribe(eq("run-1"), eq(0L), any(RunEventSubscriber.class));
  }

  @Test
  void streamWithALastEventIdReplaysOnlyWhatCameAfterIt() throws Exception {
    Run run =
        Run.queued("run-1", Environment.PUBLIC, Suite.API, Instant.parse("2026-08-31T00:00:00Z"));
    when(runService.find("run-1")).thenReturn(run);

    mockMvc
        .perform(get("/api/v1/runs/run-1/events").header("Last-Event-ID", "5"))
        .andExpect(request().asyncStarted());

    ArgumentCaptor<Long> afterSequence = ArgumentCaptor.forClass(Long.class);
    verify(broker)
        .replayAndSubscribe(eq("run-1"), afterSequence.capture(), any(RunEventSubscriber.class));
    assertThat(afterSequence.getValue()).isEqualTo(5L);
  }
}
