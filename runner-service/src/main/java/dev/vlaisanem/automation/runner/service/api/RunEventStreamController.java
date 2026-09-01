package dev.vlaisanem.automation.runner.service.api;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.events.RunEventBroker;
import dev.vlaisanem.automation.runner.service.events.RunEventSubscriber;
import dev.vlaisanem.automation.runner.service.events.RunEventSubscription;
import dev.vlaisanem.automation.runner.service.orchestration.RunService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streams one run's canonical event timeline over Server-Sent Events. HTTP contract only - all
 * replay/live delivery semantics live in {@link RunEventBroker}; this class adapts that to {@link
 * SseEmitter} and owns nothing about the run itself beyond validating it exists (see {@link
 * RunService#find}, mirroring {@link RunController#get}).
 *
 * <p>{@code Last-Event-ID} (the sequence number of the last event a reconnecting client actually
 * saw) drives the replay starting point, so a client that drops and reconnects never loses or
 * duplicates an event across the gap - the same guarantee {@link RunEventBroker#replayAndSubscribe}
 * already provides internally is simply exposed at the HTTP layer here.
 */
@RestController
@RequestMapping("/api/v1/runs")
public class RunEventStreamController {

  private final RunService runService;
  private final RunEventBroker broker;
  private final long heartbeatIntervalMillis;
  private final long emitterTimeoutMillis;
  private final ScheduledExecutorService heartbeatScheduler =
      Executors.newScheduledThreadPool(
          2,
          runnable -> {
            Thread thread = new Thread(runnable, "run-event-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
          });

  public RunEventStreamController(
      RunService runService, RunEventBroker broker, RunnerProperties properties) {
    this.runService = runService;
    this.broker = broker;
    this.heartbeatIntervalMillis = properties.sseHeartbeatInterval().toMillis();
    this.emitterTimeoutMillis = properties.sseEmitterTimeout().toMillis();
  }

  /** A single small shared pool serves every connection's heartbeat - not one thread per client. */
  @PreDestroy
  void shutdown() {
    heartbeatScheduler.shutdownNow();
  }

  @Operation(
      hidden = true,
      description =
          "Excluded from the generated OpenAPI document - OpenAPI cannot describe a named-event"
              + " SSE stream without misrepresenting it. See docs/SSE_CONTRACT_V1.md.")
  @GetMapping(value = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(
      @PathVariable String runId,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    runService.find(runId); // 404s via RunNotFoundException for an unknown runId
    long afterSequence = parseLastEventId(lastEventId);

    SseEmitter emitter = new SseEmitter(emitterTimeoutMillis);
    EmitterGuard guard = new EmitterGuard(emitter);
    DeferredSubscriptionHandle handle = new DeferredSubscriptionHandle();

    emitter.onCompletion(handle::requestClose);
    emitter.onTimeout(
        () -> {
          guard.complete();
          handle.requestClose();
        });
    emitter.onError(cause -> handle.requestClose());

    ScheduledFuture<?> heartbeat =
        heartbeatScheduler.scheduleAtFixedRate(
            () -> sendHeartbeat(guard, handle),
            heartbeatIntervalMillis,
            heartbeatIntervalMillis,
            TimeUnit.MILLISECONDS);
    handle.onClose(() -> heartbeat.cancel(false));

    try {
      RunEventSubscription subscription =
          broker.replayAndSubscribe(runId, afterSequence, new SseRunEventSubscriber(guard));
      handle.set(subscription);
    } catch (RuntimeException subscribeFailure) {
      heartbeat.cancel(false);
      throw subscribeFailure;
    }

    return emitter;
  }

  private void sendHeartbeat(EmitterGuard guard, DeferredSubscriptionHandle handle) {
    try {
      guard.emitter().send(SseEmitter.event().comment("heartbeat"));
    } catch (IOException | IllegalStateException failure) {
      handle.requestClose();
    }
  }

  private static long parseLastEventId(String lastEventId) {
    if (lastEventId == null || lastEventId.isBlank()) {
      return 0L;
    }
    long parsed;
    try {
      parsed = Long.parseLong(lastEventId.trim());
    } catch (NumberFormatException malformed) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Last-Event-ID must be a non-negative integer sequence number, got: " + lastEventId);
    }
    if (parsed < 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Last-Event-ID must not be negative, got: " + lastEventId);
    }
    return parsed;
  }

  /**
   * Translates canonical {@link RunnerEvent}s into SSE frames. All completion is routed through
   * {@link EmitterGuard} so it is never attempted twice - once for whichever of "the hub closed
   * this subscription" or "the container/client ended the connection" happens to notice first.
   */
  private static final class SseRunEventSubscriber implements RunEventSubscriber {

    private final EmitterGuard guard;

    private SseRunEventSubscriber(EmitterGuard guard) {
      this.guard = guard;
    }

    @Override
    public void onEvent(RunnerEvent event) throws IOException {
      guard
          .emitter()
          .send(
              SseEmitter.event()
                  .id(Long.toString(event.sequence()))
                  .name(event.type().name())
                  .data(event, MediaType.APPLICATION_JSON));
    }

    @Override
    public void onError(Throwable cause) {
      guard.completeWithError(cause);
    }

    @Override
    public void onComplete() {
      guard.complete();
    }
  }

  /**
   * Ensures {@link SseEmitter#complete()} / {@link SseEmitter#completeWithError} is invoked at most
   * once, regardless of which side notices the connection is over first: the servlet
   * container/client (a real disconnect or timeout, wired in {@link #stream}) and the {@link
   * RunEventBroker} subscription (the hub closing it, e.g. a slow-consumer disconnect) are two
   * independent, racing paths to the same outcome. Spring's {@code ResponseBodyEmitter} does not
   * tolerate a second completion call on an already-finished async context, so without this guard
   * whichever path loses the race would throw.
   */
  static final class EmitterGuard {

    private final SseEmitter emitter;
    private final AtomicBoolean finished = new AtomicBoolean(false);

    EmitterGuard(SseEmitter emitter) {
      this.emitter = emitter;
    }

    SseEmitter emitter() {
      return emitter;
    }

    void complete() {
      if (finished.compareAndSet(false, true)) {
        emitter.complete();
      }
    }

    void completeWithError(Throwable cause) {
      if (finished.compareAndSet(false, true)) {
        emitter.completeWithError(cause);
      }
    }
  }

  /**
   * Bridges the gap between scheduling cleanup callbacks (registered on the emitter before {@link
   * RunEventBroker#replayAndSubscribe} is even called) and that call actually returning a {@link
   * RunEventSubscription} handle to close. {@code replayAndSubscribe} starts delivering events -
   * and therefore can already be racing toward its own close - before it returns, so a
   * completion/timeout/error that fires that early must not be lost just because {@link #set} has
   * not run yet: {@link #requestClose} and {@link #set} converge on {@link #closeIfPresent},
   * exactly one of which ever sees a non-null subscription to actually close, whichever runs last.
   */
  static final class DeferredSubscriptionHandle {

    private final AtomicReference<RunEventSubscription> subscription = new AtomicReference<>();
    private final AtomicBoolean closeRequested = new AtomicBoolean(false);
    private volatile Runnable onClose = () -> {};

    void onClose(Runnable callback) {
      this.onClose = callback;
    }

    void set(RunEventSubscription value) {
      subscription.set(value);
      if (closeRequested.get()) {
        closeIfPresent();
      }
    }

    void requestClose() {
      if (closeRequested.compareAndSet(false, true)) {
        closeIfPresent();
      }
    }

    private void closeIfPresent() {
      RunEventSubscription current = subscription.getAndSet(null);
      if (current != null) {
        current.close();
        onClose.run();
      }
    }
  }
}
