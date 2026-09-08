package dev.vlaisanem.automation.runner.service.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import dev.vlaisanem.automation.runner.service.events.SseConnectionsPerIpTracker;
import dev.vlaisanem.automation.runner.service.exception.RunEventSubscriptionRejectedException;
import dev.vlaisanem.automation.runner.service.exception.RunNotFoundException;
import dev.vlaisanem.automation.runner.service.exception.RunnerRecoveringException;
import dev.vlaisanem.automation.runner.service.metrics.RunnerMetrics;
import dev.vlaisanem.automation.runner.service.orchestration.RunRecoveryService;
import dev.vlaisanem.automation.runner.service.orchestration.RunService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
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
// D3.3 - a real instance, not a MockitoBean: SseConnectionsPerIpTracker#tryAcquire is boolean, and
// a Mockito mock would default it to false, silently rejecting every existing test here unless
// explicitly stubbed true everywhere. The real component (only depends on the already-real
// RunnerProperties bean above) behaves exactly as production does, with no such pitfall.
@Import(SseConnectionsPerIpTracker.class)
class RunEventStreamControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private RunService runService;

  @MockitoBean private RunEventBroker broker;

  // D2.5 - requireRecoveryComplete() is a no-op on a plain Mockito mock (void method, nothing
  // stubbed), so every test here proceeds exactly as it did before this dependency existed.
  @MockitoBean private RunRecoveryService recoveryService;

  // D4.3.2 - recordSseRejection is void, so an unstubbed mock here is a safe no-op for every
  // existing test, exactly like recoveryService above.
  @MockitoBean private RunnerMetrics metrics;

  @Test
  void streamReturns404ForAnUnknownRunId() throws Exception {
    when(runService.find("missing")).thenThrow(new RunNotFoundException("missing"));

    mockMvc.perform(get("/api/v1/runs/missing/events")).andExpect(status().isNotFound());
  }

  /**
   * Regression test for the D3.3 review finding: one client IP must not be able to occupy every
   * global SSE slot - real {@code application.yml} default is 3 concurrent connections per IP. Uses
   * a distinct, reserved-range test IP (not the shared {@code 127.0.0.1} MockMvc default every
   * other test in this class uses, some of which never close their own connection and would
   * otherwise leak a permanently-held slot into this test's own budget) so this test's limit state
   * can never collide with theirs regardless of execution order.
   */
  @Test
  void theFourthConcurrentSseConnectionFromTheSameIpIsRejected() throws Exception {
    Run run =
        Run.queued("run-1", Environment.PUBLIC, Suite.API, Instant.parse("2026-08-31T00:00:00Z"));
    when(runService.find("run-1")).thenReturn(run);

    for (int i = 0; i < 3; i++) {
      mockMvc
          .perform(get("/api/v1/runs/run-1/events").with(remoteAddr("198.51.100.7")))
          .andExpect(request().asyncStarted());
    }

    mockMvc
        .perform(get("/api/v1/runs/run-1/events").with(remoteAddr("198.51.100.7")))
        .andExpect(status().isTooManyRequests())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .exists("Retry-After"));
  }

  private static org.springframework.test.web.servlet.request.RequestPostProcessor remoteAddr(
      String address) {
    return request -> {
      request.setRemoteAddr(address);
      return request;
    };
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
  void streamReturns503WhenTheRunnerIsStillRecoveringFromARestart() throws Exception {
    doThrow(new RunnerRecoveringException()).when(recoveryService).requireRecoveryComplete();

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
