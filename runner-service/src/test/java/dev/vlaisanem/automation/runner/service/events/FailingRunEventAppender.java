package dev.vlaisanem.automation.runner.service.events;

import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.function.LongFunction;

/** Test double that records normally until the configured event type is about to be appended. */
public final class FailingRunEventAppender implements RunEventAppender {

  private final EventType failingType;
  private final RecordingRunEventAppender delegate = new RecordingRunEventAppender();

  public FailingRunEventAppender(EventType failingType) {
    this.failingType = failingType;
  }

  @Override
  public RunnerEvent append(String runId, LongFunction<RunnerEvent> eventFactory) {
    return delegate.append(
        runId,
        sequence -> {
          RunnerEvent event = eventFactory.apply(sequence);
          if (event.type() == failingType) {
            throw new UncheckedIOException(
                "Simulated canonical journal failure for " + failingType,
                new IOException("simulated journal I/O failure"));
          }
          return event;
        });
  }

  public List<RunnerEvent> eventsFor(String runId) {
    return delegate.eventsFor(runId);
  }
}
