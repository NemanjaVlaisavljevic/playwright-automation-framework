package dev.vlaisanem.automation.runner.service.repository;

import dev.vlaisanem.automation.runner.service.domain.Run;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/**
 * Thread-safe in-memory store of {@link Run} snapshots, keyed by runId. Deliberately in-memory for
 * this MVP slice - state does not survive a restart. Persisting it (Spring Data JPA + H2) is a
 * follow-up increment once the REST/lock/process vertical slice is proven end-to-end.
 */
@Component
public class RunRepository {

  private final Map<String, Run> runs = new ConcurrentHashMap<>();

  /**
   * Inserts a brand-new run. Use {@link #transition} for every later status change. A runId is
   * meant to be unique per run (the same invariant the JSONL writer enforces with {@code
   * CREATE_NEW}), so saving one that already exists is a caller bug that should fail loudly, not
   * silently overwrite history.
   */
  public Run save(Run run) {
    return save(run, ignored -> {});
  }

  /**
   * Inserts {@code run} only after {@code beforeCommit} completes successfully. Both execute inside
   * the same per-key {@link ConcurrentHashMap#compute} operation: if the callback throws (for
   * example because the canonical {@code RUN_QUEUED} event could not be persisted), the map remains
   * unchanged and no zombie run becomes visible. The callback must not recursively mutate this
   * repository for the same runId.
   */
  public Run save(Run run, Consumer<Run> beforeCommit) {
    Objects.requireNonNull(run, "run must not be null");
    Objects.requireNonNull(beforeCommit, "beforeCommit must not be null");
    return runs.compute(
        run.runId(),
        (id, existing) -> {
          if (existing != null) {
            throw new IllegalStateException("A run already exists for runId: " + run.runId());
          }
          beforeCommit.accept(run);
          return run;
        });
  }

  /**
   * Atomically reads the current snapshot for {@code runId}, applies {@code transition} to it, and
   * stores the result - via {@link ConcurrentHashMap#compute}, which runs the remapping function
   * while holding that key's internal lock, serializing any other {@code compute}/{@code save} on
   * the same key. Without this, two threads racing to change the same run's status (e.g. a cancel
   * request and the process's own completion callback both firing near-simultaneously) could both
   * read the same starting snapshot, both pass {@code RunStateMachine} validation independently,
   * and then whichever writes last would silently discard the other's transition. With it, the
   * second attempt sees the first's already-applied result, so a genuine conflict fails loudly with
   * an {@link IllegalStateException} from {@code Run.transitionTo} instead of vanishing silently.
   *
   * <p>Also guards against {@code transition} itself returning {@code null}: {@link
   * ConcurrentHashMap#compute} treats a {@code null} remapping result as "remove this key" - left
   * unchecked, a buggy transition function would silently delete the run from the repository
   * instead of failing loudly.
   */
  public Run transition(String runId, UnaryOperator<Run> transition) {
    return runs.compute(
        runId,
        (id, current) -> {
          if (current == null) {
            throw new NoSuchElementException("No run found for runId: " + runId);
          }
          Run updated = transition.apply(current);
          if (updated == null) {
            throw new IllegalStateException(
                "Transition function must not return null for runId: " + runId);
          }
          return updated;
        });
  }

  /**
   * Atomically applies {@code transition} only if the run is not yet terminal; returns {@code
   * false} without applying anything when it's already terminal - a benign lost race with whatever
   * already finalized it (e.g. {@code cancel()} racing the process's own completion). Any other
   * {@link IllegalStateException} - an invalid transition for a reason unrelated to terminality,
   * i.e. a genuine bug - is deliberately NOT caught here and propagates to the caller. Swallowing
   * every {@code IllegalStateException} as "lost a benign race" would let a real bug leave a run
   * stuck in a non-terminal status forever.
   */
  public boolean transitionIfNonTerminal(String runId, UnaryOperator<Run> transition) {
    return transitionIfNonTerminal(runId, transition, ignored -> {});
  }

  /**
   * Variant of {@link #transitionIfNonTerminal(String, UnaryOperator)} that invokes {@code
   * beforeCommit} with the validated new snapshot while the same per-key compute lock is still
   * held. If the callback throws, {@link ConcurrentHashMap#compute} preserves the previous snapshot
   * and propagates the failure. This is the transaction boundary used by the lifecycle coordinator:
   * a canonical event must be persisted before its corresponding REST-visible state is committed.
   */
  public boolean transitionIfNonTerminal(
      String runId, UnaryOperator<Run> transition, Consumer<Run> beforeCommit) {
    Objects.requireNonNull(transition, "transition must not be null");
    Objects.requireNonNull(beforeCommit, "beforeCommit must not be null");
    AtomicBoolean applied = new AtomicBoolean(false);
    runs.compute(
        runId,
        (id, current) -> {
          if (current == null) {
            throw new NoSuchElementException("No run found for runId: " + runId);
          }
          if (current.status().isTerminal()) {
            return current;
          }
          Run updated = transition.apply(current);
          if (updated == null) {
            throw new IllegalStateException(
                "Transition function must not return null for runId: " + runId);
          }
          beforeCommit.accept(updated);
          applied.set(true);
          return updated;
        });
    return applied.get();
  }

  public Optional<Run> findById(String runId) {
    return Optional.ofNullable(runs.get(runId));
  }

  public List<Run> findAll() {
    return runs.values().stream()
        .sorted(Comparator.comparing(Run::requestedAt).reversed())
        .toList();
  }
}
