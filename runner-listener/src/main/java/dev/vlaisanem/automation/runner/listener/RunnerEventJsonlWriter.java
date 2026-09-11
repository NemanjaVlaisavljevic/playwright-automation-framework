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
import java.util.logging.Logger;

/**
 * Appends {@link RunnerEvent}s as JSON Lines to a single file, one JSON object per line, assigning
 * each event's sequence number itself. {@link #write} is thread-safe (JUnit Platform can invoke
 * listener callbacks for concurrent test classes on different threads); sequence assignment happens
 * inside the same lock as the write, so numbers can never land on disk out of order.
 *
 * <p>Uses a hardcoded {@code "\n"} separator (not the platform one), since JSON Lines is consumed
 * by other processes and a stray {@code "\r"} would corrupt a naive line-splitting reader. Opens
 * with {@link StandardOpenOption#CREATE_NEW}, not {@code APPEND}: a runId must be unique per run,
 * so an existing file means a duplicate/stale writer and should fail loudly rather than interleave
 * or duplicate sequence numbers (depends on build.gradle's {@code maxParallelForks = 1}).
 *
 * <p>{@link #close()} creates {@code completionMarker} only once the writer closes cleanly - its
 * existence is what tells a consumer the run's event log is actually complete, since a non-empty
 * file alone doesn't prove the JVM didn't crash mid-write. A failed {@link #write} permanently
 * "poisons" the writer: {@link #close()} still closes the file cleanly but skips the marker, since
 * JUnit Platform only logs (never rethrows) an exception from a listener callback.
 *
 * <p>{@code maxBytes} bounds the stream's growth: the first write that would breach it is dropped
 * (logged once, not per event) and {@link #close()} creates {@code overflowMarker} instead of
 * {@code completionMarker} - a signal consumers must treat as failure, never a clean completion.
 */
final class RunnerEventJsonlWriter implements AutoCloseable {

  // java.util.logging, not slf4j: this module has no logging-framework dependency of its own.
  private static final Logger log = Logger.getLogger(RunnerEventJsonlWriter.class.getName());

  private final ObjectMapper objectMapper;
  private final Writer writer;
  private final Path file;
  private final Path completionMarker;
  private final Path overflowMarker;
  private final long maxBytes;
  private final AtomicLong sequence = new AtomicLong(0);
  private final Object lock = new Object();
  private boolean failed;
  private boolean overflowed;
  private long bytesWritten;

  RunnerEventJsonlWriter(
      Path file,
      Path completionMarker,
      Path overflowMarker,
      long maxBytes,
      ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.file = file;
    this.completionMarker = completionMarker;
    this.overflowMarker = overflowMarker;
    this.maxBytes = maxBytes;
    try {
      // CREATE_NEW on file alone doesn't catch a stale marker left behind without its data file -
      // checked explicitly, before creating anything, so a rejected open leaves nothing behind.
      if (Files.exists(completionMarker)) {
        throw new FileAlreadyExistsException(
            completionMarker.toString(), null, "stale completion marker for " + file);
      }
      if (Files.exists(overflowMarker)) {
        throw new FileAlreadyExistsException(
            overflowMarker.toString(), null, "stale overflow marker for " + file);
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
      if (overflowed) {
        // Already logged once below; stay silent for every later dropped event.
        return;
      }
      try {
        RunnerEvent event = eventFactory.apply(sequence.incrementAndGet());
        String json = objectMapper.writeValueAsString(event);
        long lineBytes = (json.getBytes(StandardCharsets.UTF_8).length) + 1L;
        if (bytesWritten + lineBytes > maxBytes) {
          overflowed = true;
          log.warning(
              "Raw event stream "
                  + file
                  + " exceeded its configured "
                  + maxBytes
                  + "-byte limit - no further events will be written for this run");
          return;
        }
        writer.write(json);
        writer.write("\n");
        writer.flush();
        bytesWritten += lineBytes;
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
        if (overflowed) {
          Files.createFile(overflowMarker);
        } else if (!failed) {
          Files.createFile(completionMarker);
        }
      } catch (IOException exception) {
        throw new UncheckedIOException("Could not close runner event file: " + file, exception);
      }
    }
  }
}
