package dev.vlaisanem.automation.runner.service.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongFunction;
import org.springframework.stereotype.Component;

/**
 * The sole owner of each run's canonical event sequence, persisted as JSON Lines under {@link
 * RunnerProperties#journalDir()} - completely separate from runner-listener's raw {@code
 * <runId>.tests.jsonl} stream (see {@link RunnerProperties}'s own Javadoc). Every event is durably
 * written and flushed before {@link #append} returns, so a caller publishing to SSE subscribers
 * only after this returns can never advertise something a crash-recovery replay could not
 * reproduce.
 *
 * <p>Opens each run's file with {@link StandardOpenOption#CREATE_NEW}: a runId is unique per run,
 * so an already-existing file means a caller is reusing a stale runId or two callers are racing on
 * the same one - both should fail loudly rather than silently interleave or duplicate sequence
 * numbers.
 *
 * <p>A run's canonical timeline is terminal - refusing every further {@link #append} call - the
 * moment a {@link EventType#RUN_FINISHED} event is successfully written; that same moment closes
 * the file and creates its {@code .events.complete} marker. A write failure for any event
 * permanently poisons that run's journal the same way: subsequent appends are rejected, and no
 * marker is ever created, since a physically incomplete journal must never claim completeness. A
 * terminal or poisoned run's in-memory entry is kept, not dropped - {@link #readAfter} must still
 * be able to serve replay for a run long after it finished, so the rejection is enforced purely by
 * the journal's own {@code closed} flag (or, for a brand-new instance after a restart, the on-disk
 * marker/data file). {@link #shutdown()} closes whatever is still open (e.g. the service stopped
 * mid-run) without ever creating a marker for it.
 *
 * <p>This class - not the caller - also verifies that {@code eventFactory} actually used the
 * sequence number it was handed: a factory that ignores it and returns some other value is rejected
 * before anything is written, since honoring it silently would let the assigned counter and the
 * physically recorded sequence drift apart.
 *
 * <p>Also serves {@link RunEventReader#readAfter}: every successfully appended event is kept
 * in-memory (in addition to being flushed to disk), so replay never re-parses JSON or risks
 * observing a write that is still mid-flush through a second, independent file handle.
 */
@Component
public class FileBackedRunEventJournal implements RunEventAppender, RunEventReader {

  private final Path journalDir;
  private final ObjectMapper objectMapper;
  private final Map<String, RunJournal> journals = new ConcurrentHashMap<>();
  // Guards the component's own open/closed lifecycle, not per-run concurrency (each RunJournal
  // already serializes its own appends independently). append() takes the shared read lock so any
  // number of appends for different runs proceed concurrently; shutdown() takes the exclusive write
  // lock, which cannot be granted until every in-flight append has fully finished (including its
  // own computeIfAbsent/remove), and blocks any new append from starting until shutdown itself is
  // done - closing the exact race where a new RunJournal could be opened during, or survive past,
  // shutdown() without ever being closed.
  private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
  private volatile boolean terminated;

  public FileBackedRunEventJournal(RunnerProperties properties, ObjectMapper objectMapper) {
    this.journalDir = Path.of(properties.journalDir()).toAbsolutePath().normalize();
    this.objectMapper = objectMapper;
  }

  @Override
  public RunnerEvent append(String runId, LongFunction<RunnerEvent> eventFactory) {
    lifecycleLock.readLock().lock();
    try {
      if (terminated) {
        throw new RunEventJournalConflictException(
            "Canonical event journal has been shut down; run " + runId + " cannot be appended to");
      }
      RunJournal journal = journals.computeIfAbsent(runId, this::open);
      return journal.append(eventFactory);
    } finally {
      lifecycleLock.readLock().unlock();
    }
  }

  @Override
  public List<RunnerEvent> readAfter(String runId, long afterSequence) {
    RunJournal journal = journals.get(runId);
    return journal == null ? List.of() : journal.readAfter(afterSequence);
  }

  @Override
  public Optional<RunnerEvent> latest(String runId) {
    RunJournal journal = journals.get(runId);
    return journal == null ? Optional.empty() : journal.latest();
  }

  /**
   * Closes every still-open journal without creating a completion marker for any of them - a run
   * that was genuinely mid-flight when the service shut down has, by definition, no {@code
   * RUN_FINISHED} to justify one. Taking the exclusive write lock first guarantees no {@link
   * #append} call is still in flight (or can start) while this iterates and clears {@code
   * journals}, so nothing opened concurrently with shutdown can escape it. A later append attempt
   * for any runId - new or previously seen - is rejected outright once this has run.
   */
  @PreDestroy
  void shutdown() {
    lifecycleLock.writeLock().lock();
    try {
      terminated = true;
      journals.values().forEach(RunJournal::shutdownIfOpen);
      journals.clear();
    } finally {
      lifecycleLock.writeLock().unlock();
    }
  }

  private RunJournal open(String runId) {
    return new RunJournal(
        runId,
        journalDir.resolve(runId + ".events.jsonl"),
        journalDir.resolve(runId + ".events.complete"),
        objectMapper);
  }

  /** One run's open canonical journal: its own sequence counter, write lock, and terminal state. */
  private static final class RunJournal {

    private final String runId;
    private final Path dataFile;
    private final Path completionMarker;
    private final ObjectMapper objectMapper;
    private final Writer writer;
    private final Object lock = new Object();
    private final List<RunnerEvent> history = new ArrayList<>();
    private long sequence;
    private boolean closed;
    private boolean failed;

    RunJournal(String runId, Path dataFile, Path completionMarker, ObjectMapper objectMapper) {
      this.runId = runId;
      this.dataFile = dataFile;
      this.completionMarker = completionMarker;
      this.objectMapper = objectMapper;
      // CREATE_NEW below only protects dataFile. A stale/orphan completion marker left behind
      // without its data file (e.g. a runId reused after manual cleanup that missed the marker)
      // would otherwise let a brand new journal open cleanly while that marker still falsely
      // claims it is already complete - checked explicitly, and before creating anything, so a
      // rejected open never leaves a fresh data file behind either. Both this and a CREATE_NEW
      // conflict on the data file itself are the same kind of failure (this runId's timeline
      // already exists somewhere), so both surface as RunEventJournalConflictException - not
      // UncheckedIOException, which is reserved for a genuinely unexpected I/O failure.
      if (Files.exists(completionMarker)) {
        throw new RunEventJournalConflictException(
            "Canonical journal for run "
                + runId
                + " already has a completion marker (stale artifact or already finished): "
                + completionMarker);
      }
      Path parent = dataFile.toAbsolutePath().getParent();
      if (parent != null) {
        try {
          Files.createDirectories(parent);
        } catch (IOException exception) {
          throw new UncheckedIOException(
              "Could not create canonical event journal directory: " + parent, exception);
        }
      }
      try {
        this.writer =
            Files.newBufferedWriter(
                dataFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      } catch (FileAlreadyExistsException exception) {
        throw new RunEventJournalConflictException(
            "Canonical journal data file already exists for run " + runId + ": " + dataFile,
            exception);
      } catch (IOException exception) {
        throw new UncheckedIOException(
            "Could not open canonical event journal: " + dataFile, exception);
      }
    }

    RunnerEvent append(LongFunction<RunnerEvent> eventFactory) {
      synchronized (lock) {
        if (closed) {
          throw new RunEventJournalConflictException(
              "Canonical journal for run "
                  + runId
                  + " no longer accepts events (already "
                  + (failed ? "failed" : "terminal")
                  + "): "
                  + dataFile);
        }
        long nextSequence = sequence + 1;
        RunnerEvent event = eventFactory.apply(nextSequence);
        if (event.sequence() != nextSequence) {
          // Nothing has been written yet, so a rejected factory never consumes a sequence number -
          // this journal remains the sole owner of the sequence even when a caller misbehaves.
          throw new IllegalArgumentException(
              "Event factory for run "
                  + runId
                  + " returned sequence "
                  + event.sequence()
                  + " but this journal assigned "
                  + nextSequence
                  + ": "
                  + dataFile);
        }
        if (!runId.equals(event.runId())) {
          throw new IllegalArgumentException(
              "Event runId "
                  + event.runId()
                  + " does not match this journal's runId "
                  + runId
                  + ": "
                  + dataFile);
        }
        try {
          writer.write(objectMapper.writeValueAsString(event));
          writer.write("\n");
          writer.flush();
        } catch (IOException exception) {
          failed = true;
          closeQuietly();
          throw new UncheckedIOException(
              "Could not write canonical event for run " + runId, exception);
        }
        // Only committed once the write above actually succeeded - a failed write must not consume
        // a sequence number, or a retry would leave a permanent gap.
        sequence = nextSequence;
        history.add(event);
        if (event.type() == EventType.RUN_FINISHED) {
          markTerminal();
        }
        return event;
      }
    }

    /**
     * {@code history} is append-only and index {@code i} always holds sequence {@code i + 1}, so
     * {@code afterSequence} maps directly to a start index - no scan needed.
     */
    List<RunnerEvent> readAfter(long afterSequence) {
      synchronized (lock) {
        int fromIndex = (int) Math.max(0, Math.min(afterSequence, history.size()));
        return List.copyOf(history.subList(fromIndex, history.size()));
      }
    }

    Optional<RunnerEvent> latest() {
      synchronized (lock) {
        return history.isEmpty() ? Optional.empty() : Optional.of(history.get(history.size() - 1));
      }
    }

    private void markTerminal() {
      try {
        writer.close();
        Files.createFile(completionMarker);
        closed = true;
      } catch (IOException exception) {
        failed = true;
        closed = true;
        throw new UncheckedIOException(
            "Could not close canonical event journal: " + dataFile, exception);
      }
    }

    private void closeQuietly() {
      closed = true;
      try {
        writer.close();
      } catch (IOException ignored) {
        // Already failed for a different reason above - this is best-effort cleanup only.
      }
    }

    /** Closes a still-open (non-terminal, non-failed) journal without creating its marker. */
    void shutdownIfOpen() {
      synchronized (lock) {
        if (!closed) {
          closeQuietly();
        }
      }
    }
  }
}
