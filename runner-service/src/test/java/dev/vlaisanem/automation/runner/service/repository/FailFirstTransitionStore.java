package dev.vlaisanem.automation.runner.service.repository;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;

/**
 * Test double that fails exactly the <em>first</em> {@link #transitionIfNonTerminal} call, then
 * delegates normally forever after - simulating one transient store failure followed by recovery,
 * as opposed to {@link FailingRunLifecycleStore}'s "this class of write is permanently broken."
 * Proves a caller's own top-level fallback (e.g. {@code RunService.executeRun}'s {@code catch
 * (RuntimeException unexpected)} block, which itself calls {@code
 * RunLifecycleCoordinator#finishIfLive} to best-effort record {@code ERROR}) can still succeed on
 * its own retry, since only the one specific attempt this simulates ever fails.
 */
public final class FailFirstTransitionStore implements RunLifecycleStore {

  private final RunLifecycleStore delegate;
  private volatile boolean first = true;

  public FailFirstTransitionStore(RunLifecycleStore delegate) {
    this.delegate = delegate;
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
        runId, environment, suite, requestedAt, selectedTests, queuedEventFactory);
  }

  @Override
  public synchronized Optional<CommittedRunChange> transitionIfNonTerminal(
      String runId, UnaryOperator<Run> transition, LongFunction<RunnerEvent> eventFactory) {
    if (first) {
      first = false;
      throw new IllegalStateException("simulated transition failure");
    }
    return delegate.transitionIfNonTerminal(runId, transition, eventFactory);
  }

  @Override
  public Optional<RunnerEvent> appendEventIfNonTerminal(
      String runId, LongFunction<RunnerEvent> eventFactory) {
    return delegate.appendEventIfNonTerminal(runId, eventFactory);
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
  public List<Run> findNonTerminal() {
    return delegate.findNonTerminal();
  }

  @Override
  public List<RunnerEvent> readEventsAfter(String runId, long afterSequence) {
    return delegate.readEventsAfter(runId, afterSequence);
  }

  @Override
  public Optional<RunnerEvent> latestEvent(String runId) {
    return delegate.latestEvent(runId);
  }
}
