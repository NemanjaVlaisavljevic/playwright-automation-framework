package dev.vlaisanem.automation.runner.service.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import dev.vlaisanem.automation.runner.service.events.RunEventSubscription;
import dev.vlaisanem.automation.runner.service.events.SseConnectionsPerIpTracker;
import dev.vlaisanem.automation.runner.service.exception.RunEventSubscriptionRejectedException;
import dev.vlaisanem.automation.runner.service.exception.RunNotFoundException;
import dev.vlaisanem.automation.runner.service.exception.RunnerRecoveringException;
import dev.vlaisanem.automation.runner.service.metrics.RunnerMetrics;
import dev.vlaisanem.automation.runner.service.orchestration.RunRecoveryService;
import dev.vlaisanem.automation.runner.service.orchestration.RunService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-contract tests: status-code mapping and {@code Last-Event-ID} parsing. Cleanup/atomicity
 * guarantees are proven separately and deterministically in {@link DeferredSubscriptionHandleTest}
 * and {@link EmitterGuardTest}, since Spring's {@code ResponseBodyEmitter.Handler} is
 * package-private and can't be driven from a test here.
 */
@WebMvcTest(controllers = {RunEventStreamController.class, RunExceptionHandler.class})
@EnableConfigurationProperties(RunnerProperties.class)
// Real instance, not a MockitoBean: SseConnectionsPerIpTracker#tryAcquire is boolean, and a mock
// would default to false, silently rejecting every test here unless stubbed true everywhere.
@Import({
  SseConnectionsPerIpTracker.class,
  RunEventStreamControllerTest.TestMeterRegistryConfig.class
})
class RunEventStreamControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private RunService runService;

  @MockitoBean private RunEventBroker broker;

  // requireRecoveryComplete() is a no-op on an unstubbed mock (void method), so existing tests
  // are unaffected by default.
  @MockitoBean private RunRecoveryService recoveryService;

  // recordSseRejection is void, so an unstubbed mock is a safe no-op, like recoveryService above.
  @MockitoBean private RunnerMetrics metrics;

  @Test
  void streamReturns404ForAnUnknownRunId() throws Exception {
    when(runService.find("missing")).thenThrow(new RunNotFoundException("missing"));

    mockMvc.perform(get("/api/v1/runs/missing/events")).andExpect(status().isNotFound());
  }

  /**
   * One client IP must not occupy every global SSE slot - {@code application.yml} defaults to 3
   * concurrent connections per IP. Uses a distinct reserved-range test IP (not the shared {@code
   * 127.0.0.1} MockMvc default other tests use, some of which never close their connection and
   * could leak a slot) so this test's limit state can't collide with theirs.
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

  /**
   * A terminal run's broker-side {@code onComplete} must release the per-IP slot immediately,
   * without waiting for Spring's async servlet context to separately notice completion. This test
   * never triggers a servlet-level callback (MockMvc's {@code asyncStarted()} leaves the request
   * open) - only the captured {@link RunEventSubscriber#onComplete()} runs, mirroring what {@code
   * RunEventHub}'s {@code Subscription.deliverLoop} calls on a run's final event.
   */
  @Test
  void aBrokerSideOnCompleteReleasesThePerIpSlotImmediately() throws Exception {
    Run run =
        Run.queued("run-1", Environment.PUBLIC, Suite.API, Instant.parse("2026-08-31T00:00:00Z"));
    when(runService.find("run-1")).thenReturn(run);
    // replayAndSubscribe never returns null in production; stub it realistically so
    // DeferredSubscriptionHandle has a subscription to close.
    when(broker.replayAndSubscribe(eq("run-1"), anyLong(), any()))
        .thenReturn(mock(RunEventSubscription.class));

    for (int i = 0; i < 3; i++) {
      mockMvc
          .perform(get("/api/v1/runs/run-1/events").with(remoteAddr("203.0.113.9")))
          .andExpect(request().asyncStarted());
    }

    // The cap is now full; a 4th connection from the same IP is rejected.
    mockMvc
        .perform(get("/api/v1/runs/run-1/events").with(remoteAddr("203.0.113.9")))
        .andExpect(status().isTooManyRequests());

    ArgumentCaptor<RunEventSubscriber> subscribers =
        ArgumentCaptor.forClass(RunEventSubscriber.class);
    verify(broker, times(3)).replayAndSubscribe(eq("run-1"), anyLong(), subscribers.capture());
    RunEventSubscriber firstSubscriber = subscribers.getAllValues().get(0);

    // The broker decides the run is done and notifies the subscriber directly; no emitter/servlet
    // callback runs.
    firstSubscriber.onComplete();
    // Idempotency: a second terminal notification must never free a second slot.
    firstSubscriber.onComplete();

    // Exactly one slot was released: one new connection from the same IP now succeeds...
    mockMvc
        .perform(get("/api/v1/runs/run-1/events").with(remoteAddr("203.0.113.9")))
        .andExpect(request().asyncStarted());
    // ...but the cap fills again immediately, proving the double onComplete() above didn't
    // release a second slot.
    mockMvc
        .perform(get("/api/v1/runs/run-1/events").with(remoteAddr("203.0.113.9")))
        .andExpect(status().isTooManyRequests());
  }

  /** Mirrors {@link #aBrokerSideOnCompleteReleasesThePerIpSlotImmediately} for the error path. */
  @Test
  void aBrokerSideOnErrorReleasesThePerIpSlotImmediately() throws Exception {
    Run run =
        Run.queued("run-1", Environment.PUBLIC, Suite.API, Instant.parse("2026-08-31T00:00:00Z"));
    when(runService.find("run-1")).thenReturn(run);
    when(broker.replayAndSubscribe(eq("run-1"), anyLong(), any()))
        .thenReturn(mock(RunEventSubscription.class));

    for (int i = 0; i < 3; i++) {
      mockMvc
          .perform(get("/api/v1/runs/run-1/events").with(remoteAddr("198.51.100.42")))
          .andExpect(request().asyncStarted());
    }

    ArgumentCaptor<RunEventSubscriber> subscribers =
        ArgumentCaptor.forClass(RunEventSubscriber.class);
    verify(broker, times(3)).replayAndSubscribe(eq("run-1"), anyLong(), subscribers.capture());
    subscribers.getAllValues().get(0).onError(new RuntimeException("simulated transport failure"));

    mockMvc
        .perform(get("/api/v1/runs/run-1/events").with(remoteAddr("198.51.100.42")))
        .andExpect(request().asyncStarted());
  }

  /**
   * Provides the plain {@link MeterRegistry} {@link SseConnectionsPerIpTracker} needs for its
   * gauge; {@code @WebMvcTest} doesn't auto-configure Micrometer. Must be
   * {@code @TestConfiguration}, not plain {@code @Configuration} - the latter gets misdetected as
   * the slice's primary configuration and silently drops {@code @WebMvcTest}'s controller
   * registration entirely.
   */
  @TestConfiguration
  static class TestMeterRegistryConfig {
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }
}
