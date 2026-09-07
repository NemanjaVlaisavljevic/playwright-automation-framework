package dev.vlaisanem.automation.runner.service.repository;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;

/**
 * Fast, in-memory {@link RunLifecycleStore} for unit tests that exercise orchestration ( {@code
 * RunEventBroker}/{@code RunLifecycleCoordinator}/{@code RunService}) without needing a real
 * Testcontainers Postgres for every one of them - {@code JdbcRunStore} is the one production
 * implementation, proven against a real database in {@code databaseIntegrationTest}.
 *
 * <p>Deliberately mirrors {@code JdbcRunStore}'s exact semantics rather than a simplified
 * approximation: the same shared {@link RunEventValidation} calls (so a fake accepting something
 * the real store would reject, or vice versa, is impossible by construction, not just by
 * convention), and a genuine per-run monitor lock via {@code synchronized} - matching {@code SELECT
 * ... FOR UPDATE}'s real blocking behavior closely enough that a test racing two threads against
 * the same {@code runId} still proves real serialization, not merely a single-threaded happy path.
 */
public final class FakeRunLifecycleStore implements RunLifecycleStore {

  private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();
  private final RunLockStripes lockStripes = new RunLockStripes();

  private static final class RunRecord {
    // findById()/findAll() read this field without holding the per-run lock (they must stay
    // lock-free to avoid serializing on a run that a writer might be blocking on), so plain
    // ConcurrentHashMap visibility isn't enough - volatile is what actually guarantees a reader on
    // another thread observes the writer's most recent assignment under the JMM.
    private volatile Run run;
    private long nextEventSequence = 1;
    private final List<RunnerEvent> events = new ArrayList<>();
    // D4.1 - mirrors runs.cleanup_started_at/artifacts_purge_started_at/artifacts_purged_at.
    private volatile Instant cleanupStartedAt;
    private volatile Instant artifactsPurgeStartedAt;
    private volatile Instant artifactsPurgedAt;
  }

  private Object lockFor(String runId) {
    return lockStripes.lockFor(runId);
  }

  @Override
  public CommittedRunChange queue(
      String runId,
      Environment environment,
      Suite suite,
      Instant requestedAt,
      List<SelectedTestSnapshot> selectedTests,
      LongFunction<RunnerEvent> queuedEventFactory) {
    synchronized (lockFor(runId)) {
      if (runs.containsKey(runId)) {
        throw new IllegalStateException("A run already exists for runId: " + runId);
      }
      Run run = Run.queued(runId, environment, suite, requestedAt, selectedTests);
      RunnerEvent queuedEvent = queuedEventFactory.apply(1L);
      RunEventValidation.requireQueuedEvent(queuedEvent, runId);
      RunRecord record = new RunRecord();
      record.run = run;
      record.nextEventSequence = 2;
      record.events.add(queuedEvent);
      runs.put(runId, record);
      return new CommittedRunChange(run, queuedEvent);
    }
  }

  @Override
  public Optional<CommittedRunChange> transitionIfNonTerminal(
      String runId, UnaryOperator<Run> transition, LongFunction<RunnerEvent> eventFactory) {
    synchronized (lockFor(runId)) {
      RunRecord record = runs.get(runId);
      if (record == null) {
        throw new NoSuchElementException("No run found for runId: " + runId);
      }
      if (record.run.status().isTerminal()) {
        return Optional.empty();
      }
      Run before = record.run;
      Run updated = transition.apply(before);
      RunEventValidation.requireSameIdentity(before, updated);
      RunEventValidation.requireReachableTransition(before, updated);
      long nextSequence = record.nextEventSequence;
      RunnerEvent committedEvent = eventFactory == null ? null : eventFactory.apply(nextSequence);
      if (committedEvent != null) {
        RunEventValidation.requireMatchingEvent(committedEvent, runId, nextSequence);
      }
      RunEventValidation.requireLifecycleEventMatches(committedEvent, updated.status());
      if (committedEvent != null) {
        record.events.add(committedEvent);
        nextSequence += 1;
      }
      record.run = updated;
      record.nextEventSequence = nextSequence;
      return Optional.of(new CommittedRunChange(updated, committedEvent));
    }
  }

  @Override
  public Optional<RunnerEvent> appendEventIfNonTerminal(
      String runId, LongFunction<RunnerEvent> eventFactory) {
    synchronized (lockFor(runId)) {
      RunRecord record = runs.get(runId);
      if (record == null) {
        throw new NoSuchElementException("No run found for runId: " + runId);
      }
      if (record.run.status().isTerminal()) {
        return Optional.empty();
      }
      long sequence = record.nextEventSequence;
      RunnerEvent event = eventFactory.apply(sequence);
      RunEventValidation.requireMatchingEvent(event, runId, sequence);
      RunEventValidation.requireAppendOnlyEventType(event);
      record.events.add(event);
      record.nextEventSequence = sequence + 1;
      return Optional.of(event);
    }
  }

  @Override
  public Optional<Run> findById(String runId) {
    RunRecord record = runs.get(runId);
    if (record == null || record.cleanupStartedAt != null) {
      return Optional.empty();
    }
    return Optional.of(record.run);
  }

  @Override
  public List<Run> findAll() {
    return runs.values().stream()
        .filter(record -> record.cleanupStartedAt == null)
        .map(record -> record.run)
        .sorted(Comparator.comparing(Run::requestedAt).reversed())
        .toList();
  }

  @Override
  public List<Run> findNonTerminal() {
    return runs.values().stream()
        .map(record -> record.run)
        .filter(run -> !run.status().isTerminal())
        .sorted(Comparator.comparing(Run::requestedAt))
        .toList();
  }

  @Override
  public List<RunnerEvent> readEventsAfter(String runId, long afterSequence) {
    RunRecord record = runs.get(runId);
    if (record == null) {
      return List.of();
    }
    synchronized (lockFor(runId)) {
      return record.events.stream().filter(event -> event.sequence() > afterSequence).toList();
    }
  }

  @Override
  public Optional<RunnerEvent> latestEvent(String runId) {
    RunRecord record = runs.get(runId);
    if (record == null) {
      return Optional.empty();
    }
    synchronized (lockFor(runId)) {
      return record.events.isEmpty()
          ? Optional.empty()
          : Optional.of(record.events.get(record.events.size() - 1));
    }
  }

  @Override
  public List<String> findEligibleForCleanup(Instant now, Duration maxAge, int maxCount) {
    Instant cutoff = now.minus(maxAge);
    List<Map.Entry<String, RunRecord>> ranked =
        runs.entrySet().stream()
            .filter(
                e ->
                    e.getValue().cleanupStartedAt == null && e.getValue().run.status().isTerminal())
            .sorted(
                Comparator.<Map.Entry<String, RunRecord>, Instant>comparing(
                        e -> e.getValue().run.finishedAt(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                    .reversed()
                    .thenComparing(
                        (Map.Entry<String, RunRecord> e) -> e.getValue().run.requestedAt(),
                        Comparator.reverseOrder())
                    .thenComparing(
                        (Map.Entry<String, RunRecord> e) -> e.getKey(), Comparator.reverseOrder()))
            .toList();
    List<String> eligible = new ArrayList<>();
    for (int i = 0; i < ranked.size(); i++) {
      Map.Entry<String, RunRecord> entry = ranked.get(i);
      int rank = i + 1;
      Instant finishedAt = entry.getValue().run.finishedAt();
      boolean tooOld = finishedAt != null && finishedAt.isBefore(cutoff);
      if (rank > maxCount || tooOld) {
        eligible.add(entry.getKey());
      }
    }
    return eligible;
  }

  @Override
  public List<String> findPendingCleanup() {
    return runs.entrySet().stream()
        .filter(e -> e.getValue().cleanupStartedAt != null)
        .map(Map.Entry::getKey)
        .toList();
  }

  @Override
  public boolean claimForCleanup(String runId) {
    RunRecord record = runs.get(runId);
    if (record == null || !record.run.status().isTerminal()) {
      return false;
    }
    synchronized (lockFor(runId)) {
      if (record.cleanupStartedAt != null) {
        return false;
      }
      record.cleanupStartedAt = Instant.now();
      return true;
    }
  }

  @Override
  public void deleteRun(String runId) {
    runs.remove(runId);
  }

  @Override
  public List<String> findEligibleForArtifactPurge(Instant now, Duration maxAge) {
    Instant cutoff = now.minus(maxAge);
    return runs.entrySet().stream()
        .filter(e -> e.getValue().artifactsPurgeStartedAt == null)
        .filter(e -> e.getValue().run.status().isTerminal())
        .filter(
            e -> {
              Instant finishedAt = e.getValue().run.finishedAt();
              return finishedAt != null && finishedAt.isBefore(cutoff);
            })
        .map(Map.Entry::getKey)
        .toList();
  }

  @Override
  public List<String> findPendingArtifactPurge() {
    return runs.entrySet().stream()
        .filter(
            e ->
                e.getValue().artifactsPurgeStartedAt != null
                    && e.getValue().artifactsPurgedAt == null)
        .map(Map.Entry::getKey)
        .toList();
  }

  @Override
  public boolean claimForArtifactPurge(String runId) {
    RunRecord record = runs.get(runId);
    if (record == null || !record.run.status().isTerminal()) {
      return false;
    }
    synchronized (lockFor(runId)) {
      if (record.artifactsPurgeStartedAt != null) {
        return false;
      }
      record.artifactsPurgeStartedAt = Instant.now();
      return true;
    }
  }
}
