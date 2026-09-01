package dev.vlaisanem.automation.runner.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;

/**
 * Appends {@link RunnerEvent}s as JSON Lines to a single file, one JSON object per line, assigning
 * each event's sequence number itself. {@link #write} is safe to call from multiple threads
 * concurrently - this project runs test classes concurrently (see junit-platform.properties), so
 * JUnit Platform can invoke listener callbacks for different tests on different threads at the same
 * time.
 *
 * <p>Sequence assignment happens inside the same lock as the actual write - assigning it a step
 * earlier (e.g. an {@link AtomicLong} in the caller, read before calling {@link #write}) would let
 * two threads race between "take a sequence number" and "append to the file", so a lower sequence
 * number could physically land after a higher one on disk even though numbers stay unique.
 *
 * <p>Uses a hardcoded {@code "\n"} line separator rather than the platform line separator, since
 * JSON Lines is a line-oriented format consumed by other processes - a stray {@code "\r"} on
 * Windows would corrupt the last field of every line for a naive line-splitting reader.
 *
 * <p>Opens with {@link StandardOpenOption#CREATE_NEW}, not {@code APPEND}: a runId is meant to be
 * unique per run, so a file that already exists means either two writers targeting the same runId
 * (this class's in-JVM lock cannot protect against a second, separate JVM process doing the same)
 * or a caller reusing a stale runId - both would otherwise silently interleave or duplicate
 * sequence numbers instead of failing loudly. See the build.gradle {@code maxParallelForks = 1}
 * guarantee this depends on.
 *
 * <p>{@link #close()} creates the given {@code completionMarker} file once the writer itself is
 * closed - a non-empty JSONL file alone does not prove the run actually finished (the JVM could
 * have crashed mid-write), so a consumer should require this marker before trusting the file as
 * complete. The marker path is taken explicitly rather than derived from {@code file} (e.g. by
 * suffixing {@code ".complete"}), since callers may want a marker name that does not simply extend
 * the data file's own name - see {@code RunnerEventTestExecutionListener}'s {@code raw/} layout. If
 * any {@link #write} call ever failed, the writer is permanently "poisoned": {@link #close()} still
 * closes the underlying file cleanly (so a caller can always close it safely) but deliberately
 * skips creating the marker, since JUnit Platform catches and merely logs an exception thrown from
 * a listener callback - without this, a failed write could be silently swallowed by JUnit while the
 * marker still claimed the run's event log was complete.
 */
final class RunnerEventJsonlWriter implements AutoCloseable {

  private final ObjectMapper objectMapper;
  private final Writer writer;
  private final Path file;
  private final Path completionMarker;
  private final AtomicLong sequence = new AtomicLong(0);
  private final Object lock = new Object();
  private boolean failed;

  RunnerEventJsonlWriter(Path file, Path completionMarker, ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.file = file;
    this.completionMarker = completionMarker;
    try {
      // CREATE_NEW below only protects file. A stale/orphan completion marker left behind without
      // its data file (e.g. a runId reused after manual cleanup that missed the marker) would
      // otherwise let a brand new writer open cleanly while that marker still falsely claims it is
      // already complete - checked explicitly, and before creating anything, so a rejected open
      // never leaves a fresh data file behind either.
      if (Files.exists(completionMarker)) {
        throw new FileAlreadyExistsException(
            completionMarker.toString(), null, "stale completion marker for " + file);
      }
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      this.writer =
          Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    } catch (IOException exception) {
      throw new UncheckedIOException("Could not open runner event file: " + file, exception);
    }
  }

  void write(LongFunction<RunnerEvent> eventFactory) {
    synchronized (lock) {
      try {
        RunnerEvent event = eventFactory.apply(sequence.incrementAndGet());
        writer.write(objectMapper.writeValueAsString(event));
        writer.write("\n");
        writer.flush();
      } catch (IOException exception) {
        failed = true;
        throw new UncheckedIOException("Could not write runner event", exception);
      } catch (RuntimeException exception) {
        failed = true;
        throw exception;
      }
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      try {
        writer.close();
        if (!failed) {
          Files.createFile(completionMarker);
        }
      } catch (IOException exception) {
        throw new UncheckedIOException("Could not close runner event file: " + file, exception);
      }
    }
  }
}
