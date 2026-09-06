package dev.vlaisanem.automation.runner.service.repository;

import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;

/**
 * Test double proving the D2.3 atomic-store failure mode: wraps a delegate store, throwing from
 * within the caller-supplied event factory itself (before the delegate ever commits anything) every
 * time it would produce an event of {@code failingType} - simulating a real store-level failure (a
 * broken connection, a constraint violation) at exactly the same point {@code JdbcRunStore}'s own
 * transaction would roll back. Unlike {@link FailFirstTransitionStore}, this fails <em>every</em>
 * matching write, not just the first - the right tool for "this class of write is permanently
 * broken" scenarios (e.g. proving a run can never be falsely reported as terminal once its
 * finishing write can never succeed), as opposed to "one transient failure, then recovery."
 */
public final class FailingRunLifecycleStore implements RunLifecycleStore {

  private final RunLifecycleStore delegate;
  private final EventType failingType;

  public FailingRunLifecycleStore(RunLifecycleStore delegate, EventType failingType) {
    this.delegate = delegate;
    this.failingType = failingType;
  }

  @Override
  public CommittedRunChange queue(
      String runId,
      Environment environment,
      Suite suite,
      Instant requestedAt,
      List<SelectedTestSnapshot> selectedTests,
      LongFunction<RunnerEvent> queuedEventFactory) {
    return delegate.queue(
        runId, environment, suite, requestedAt, selectedTests, wrap(queuedEventFactory));
  }

  @Override
  public Optional<CommittedRunChange> transitionIfNonTerminal(
      String runId, UnaryOperator<Run> transition, LongFunction<RunnerEvent> eventFactory) {
    return delegate.transitionIfNonTerminal(
        runId, transition, eventFactory == null ? null : wrap(eventFactory));
  }

  @Override
  public Optional<RunnerEvent> appendEventIfNonTerminal(
      String runId, LongFunction<RunnerEvent> eventFactory) {
    return delegate.appendEventIfNonTerminal(runId, wrap(eventFactory));
  }

  @Override
  public Optional<Run> findById(String runId) {
    return delegate.findById(runId);
  }

  @Override
  public List<Run> findAll() {
    return delegate.findAll();
  }

  @Override
  public List<RunnerEvent> readEventsAfter(String runId, long afterSequence) {
    return delegate.readEventsAfter(runId, afterSequence);
  }

  @Override
  public Optional<RunnerEvent> latestEvent(String runId) {
    return delegate.latestEvent(runId);
  }

  private LongFunction<RunnerEvent> wrap(LongFunction<RunnerEvent> factory) {
    return sequence -> {
      RunnerEvent event = factory.apply(sequence);
      if (event.type() == failingType) {
        throw new UncheckedIOException(
            "Simulated store failure for " + failingType,
            new IOException("simulated store I/O failure"));
      }
      return event;
    };
  }
}
