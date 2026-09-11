package dev.vlaisanem.automation.runner.service.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.logging.MdcScope;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tails one run's raw {@code <runId>.tests.jsonl} (written by runner-listener, a separate process)
 * and forwards each validated line into the canonical journal via {@link RunEventAppender}, which
 * assigns it a fresh canonical sequence - the raw file's own source sequence only proves the
 * listener's internal ordering, not the cross-run-lifecycle one a dashboard needs.
 *
 * <p>Polls rather than relying on filesystem notifications: {@code WatchService} can coalesce
 * several rapid writes into a single event on Windows, so polling is the only way to guarantee no
 * burst of test events is missed.
 *
 * <p>Runs on its own daemon thread until either the raw {@code .tests.complete} marker appears (a
 * clean listener shutdown) or {@link #stopAndAwaitFinished} is called (cancelled, timed out, or
 * force-killed). A trailing unterminated line left by an abrupt kill is discarded, not treated as a
 * validation failure, but only when the marker was never observed; the marker appearing at all is
 * this class's unconditional promise that the stream is complete and consistent, so a trailing line
 * or missing data file alongside it can only mean corruption.
 *
 * <p>Backed by a real {@link ExecutorService#submit}, not a bare {@code
 * CompletableFuture.supplyAsync} task, whose {@code cancel(true)} would not actually interrupt the
 * running computation - {@link #stopAndAwaitFinished} depends on a genuine interrupt to promptly
 * break a stuck poll loop out of {@code Thread.sleep}.
 *
 * <p>A malformed line, a source-sequence gap/duplicate, an event for the wrong runId or an
 * unexpected type, an unsupported {@code schemaVersion}, or a marker-consistency violation stops
 * ingestion immediately with {@link IngestionResult#valid() valid() == false}.
 */
public final class ListenerEventIngestor {

  private static final int MAX_CHUNK_BYTES = 65536;
  private static final byte NEWLINE = (byte) '\n';

  private final String runId;
  private final Path dataFile;
  private final Path completionMarker;
  private final Path overflowMarker;
  private final RunEventAppender eventAppender;
  private final ObjectMapper objectMapper;
  private final Duration pollInterval;
  private final int maxChunkBytes;
  private final AtomicBoolean stopRequested = new AtomicBoolean(false);
  private final AtomicReference<IngestionResult> terminalResult = new AtomicReference<>();
  private final ExecutorService executor;
  private final Future<IngestionResult> future;
  private RandomAccessFile raf;

  ListenerEventIngestor(
      String runId,
      Path dataFile,
      Path completionMarker,
      Path overflowMarker,
      RunEventAppender eventAppender,
      ObjectMapper objectMapper,
      Duration pollInterval) {
    this(
        runId,
        dataFile,
        completionMarker,
        overflowMarker,
        eventAppender,
        objectMapper,
        pollInterval,
        MAX_CHUNK_BYTES);
  }

  /**
   * Test-only entry point: a small {@code maxChunkBytes} deterministically forces a read boundary
   * to land in the middle of a multi-byte UTF-8 character without needing a multi-megabyte fixture.
   */
  ListenerEventIngestor(
      String runId,
      Path dataFile,
      Path completionMarker,
      Path overflowMarker,
      RunEventAppender eventAppender,
      ObjectMapper objectMapper,
      Duration pollInterval,
      int maxChunkBytes) {
    this.runId = runId;
    this.dataFile = dataFile;
    this.completionMarker = completionMarker;
    this.overflowMarker = overflowMarker;
    this.eventAppender = eventAppender;
    this.objectMapper = objectMapper;
    this.pollInterval = pollInterval;
    this.maxChunkBytes = maxChunkBytes;
    this.executor =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "listener-event-ingestor-" + runId);
              thread.setDaemon(true);
              return thread;
            });
    // This executor's worker thread is distinct from whatever thread called this constructor, so
    // MDC doesn't carry runId onto it automatically.
    this.future = executor.submit(() -> MdcScope.withMdc("runId", runId, this::runLoop));
  }

  /**
   * Signals the poll loop to finish as soon as it next wakes and waits up to {@code timeout} for it
   * to actually do so. Safe to call more than once - {@link Future#get} on an already-completed
   * future simply returns the same result again.
   *
   * <p>On timeout, {@link Future#cancel(boolean) cancel(true)} plus {@link
   * ExecutorService#shutdownNow()} guarantee the background thread cannot outlive this call to
   * later append an event after the caller has moved on and closed the journal with {@code
   * RUN_FINISHED}.
   */
  public IngestionResult stopAndAwaitFinished(Duration timeout) {
    IngestionResult cached = terminalResult.get();
    if (cached != null) {
      return cached;
    }
    stopRequested.set(true);
    IngestionResult result;
    try {
      result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException timedOut) {
      interruptAndShutdownNow();
      result =
          IngestionResult.invalid(
              "Listener event ingestion for run " + runId + " did not stop within " + timeout);
    } catch (CancellationException cancelled) {
      // A concurrent caller's own timeout already cancelled the underlying task while this call
      // was still waiting on it.
      interruptAndShutdownNow();
      result =
          IngestionResult.invalid(
              "Listener event ingestion for run "
                  + runId
                  + " was cancelled by a concurrent caller");
    } catch (ExecutionException executionFailure) {
      Throwable cause = executionFailure.getCause();
      result =
          IngestionResult.invalid(
              "Listener event ingestion for run "
                  + runId
                  + " failed unexpectedly: "
                  + (cause != null ? cause.getMessage() : executionFailure.getMessage()));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      // The caller's wait was interrupted, not the ingestion thread - stop the underlying task too,
      // or it could keep running and appending events after this method has returned.
      interruptAndShutdownNow();
      result =
          IngestionResult.invalid(
              "Interrupted while awaiting listener event ingestion for run " + runId);
    } finally {
      executor.shutdown();
    }
    // First caller to resolve a terminal result wins; every concurrent caller converges on it.
    return terminalResult.compareAndSet(null, result) ? result : terminalResult.get();
  }

  private void interruptAndShutdownNow() {
    future.cancel(true);
    executor.shutdownNow();
  }

  private IngestionResult runLoop() {
    long lineNumber = 0;
    long consumedOffset = 0;
    long lastSourceSequence = 0;
    byte[] pending = new byte[0];
    try {
      while (true) {
        byte[] newBytes = readAvailable();
        if (newBytes.length > 0) {
          byte[] combined = concat(pending, newBytes);
          int start = 0;
          int newlineIndex;
          while ((newlineIndex = indexOf(combined, start, NEWLINE)) >= 0) {
            int lineLength = newlineIndex - start;
            lineNumber++;
            long lineStartOffset = consumedOffset;
            consumedOffset += lineLength + 1;
            if (lineLength > 0) {
              String line;
              try {
                line = decodeStrictUtf8(combined, start, lineLength);
              } catch (CharacterCodingException invalidUtf8) {
                throw new RawEventValidationException(
                    diagnostic(
                        lineNumber,
                        lineStartOffset,
                        lastSourceSequence + 1,
                        null,
                        "invalid UTF-8 byte sequence: " + invalidUtf8.getMessage()));
              }
              lastSourceSequence =
                  validateAndForward(line, lineNumber, lineStartOffset, lastSourceSequence);
            }
            start = newlineIndex + 1;
          }
          pending = Arrays.copyOfRange(combined, start, combined.length);
        }
        boolean complete = Files.exists(completionMarker);
        boolean overflow = Files.exists(overflowMarker);
        boolean stop = stopRequested.get();
        if (newBytes.length == 0 && (complete || overflow || stop)) {
          // Checked before completion-marker handling, and unconditionally: the writer already
          // stopped appending once it hit its configured cap, so this is its own explicit
          // validation failure, never folded into the "stopped without a marker" tolerance below,
          // which exists for a different case (an abrupt kill).
          if (overflow) {
            throw new RawEventValidationException(
                diagnostic(
                    lineNumber + 1,
                    consumedOffset,
                    lastSourceSequence + 1,
                    null,
                    "raw event stream exceeded its configured size limit and was truncated by the"
                        + " writer"));
          }
          if (complete) {
            if (pending.length > 0) {
              throw new RawEventValidationException(
                  diagnostic(
                      lineNumber + 1,
                      consumedOffset,
                      lastSourceSequence + 1,
                      null,
                      "raw stream is marked complete but ends with an unterminated trailing"
                          + " line"));
            }
            if (raf == null) {
              throw new RawEventValidationException(
                  diagnostic(
                      lineNumber + 1,
                      consumedOffset,
                      lastSourceSequence + 1,
                      null,
                      "completion marker exists but the raw data file was never created"));
            }
          }
          return IngestionResult.valid(complete);
        }
        if (newBytes.length == 0) {
          Thread.sleep(pollInterval.toMillis());
        }
      }
    } catch (RawEventValidationException validationFailure) {
      return IngestionResult.invalid(validationFailure.getMessage());
    } catch (IOException ioFailure) {
      return IngestionResult.invalid(
          "I/O failure reading raw event stream " + dataFile + ": " + ioFailure.getMessage());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return IngestionResult.invalid("Interrupted while reading raw event stream " + dataFile);
    } catch (RuntimeException unexpected) {
      return IngestionResult.invalid(
          "Unexpected failure ingesting raw events for run "
              + runId
              + ": "
              + unexpected.getMessage());
    } finally {
      closeQuietly();
    }
  }

  private long validateAndForward(
      String line, long lineNumber, long lineStartOffset, long lastSourceSequence) {
    RunnerEvent event;
    try {
      event = objectMapper.readValue(line, RunnerEvent.class);
    } catch (IOException malformed) {
      throw new RawEventValidationException(
          diagnostic(
              lineNumber,
              lineStartOffset,
              lastSourceSequence + 1,
              null,
              "malformed JSON: " + malformed.getMessage()));
    }
    if (!RunnerEvent.CURRENT_SCHEMA_VERSION.equals(event.schemaVersion())) {
      throw new RawEventValidationException(
          diagnostic(
              lineNumber,
              lineStartOffset,
              lastSourceSequence + 1,
              event.sequence(),
              "unsupported schemaVersion "
                  + event.schemaVersion()
                  + " (expected "
                  + RunnerEvent.CURRENT_SCHEMA_VERSION
                  + ")"));
    }
    if (!runId.equals(event.runId())) {
      throw new RawEventValidationException(
          diagnostic(
              lineNumber,
              lineStartOffset,
              lastSourceSequence + 1,
              event.sequence(),
              "runId mismatch: expected " + runId + " but was " + event.runId()));
    }
    if (!event.type().isTestLevel() && !event.type().isStepLevel()) {
      throw new RawEventValidationException(
          diagnostic(
              lineNumber,
              lineStartOffset,
              lastSourceSequence + 1,
              event.sequence(),
              "unexpected non-test-level, non-step-level event type " + event.type()));
    }
    long expectedSequence = lastSourceSequence + 1;
    if (event.sequence() != expectedSequence) {
      String reason =
          event.sequence() <= lastSourceSequence
              ? "duplicate source sequence"
              : "gap in source sequence";
      throw new RawEventValidationException(
          diagnostic(lineNumber, lineStartOffset, expectedSequence, event.sequence(), reason));
    }
    eventAppender.append(
        runId, canonicalSequence -> withCanonicalSequence(event, canonicalSequence));
    return event.sequence();
  }

  private String diagnostic(
      long lineNumber, long byteOffset, long expectedSequence, Long actualSequence, String reason) {
    return "Raw event validation failed for "
        + dataFile
        + " (line "
        + lineNumber
        + ", byte offset "
        + byteOffset
        + "): "
        + reason
        + " [expectedSourceSequence="
        + expectedSequence
        + ", actualSourceSequence="
        + (actualSequence == null ? "n/a" : actualSequence)
        + ", runId="
        + runId
        + "]";
  }

  private byte[] readAvailable() throws IOException {
    if (raf == null) {
      if (!Files.exists(dataFile)) {
        return new byte[0];
      }
      raf = new RandomAccessFile(dataFile.toFile(), "r");
    }
    long available = raf.length() - raf.getFilePointer();
    if (available <= 0) {
      return new byte[0];
    }
    byte[] buffer = new byte[(int) Math.min(available, maxChunkBytes)];
    raf.readFully(buffer);
    return buffer;
  }

  private void closeQuietly() {
    if (raf != null) {
      try {
        raf.close();
      } catch (IOException ignored) {
        // Best-effort cleanup only - the ingestion result has already been decided.
      }
    }
  }

  /**
   * Decodes strictly - unlike {@code new String(bytes, UTF_8)}, which silently replaces invalid
   * bytes with U+FFFD, this throws on malformed input, since the JSON could otherwise stay
   * syntactically valid while a field's value silently changes underneath it.
   */
  private static String decodeStrictUtf8(byte[] bytes, int offset, int length)
      throws CharacterCodingException {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    return decoder.decode(ByteBuffer.wrap(bytes, offset, length)).toString();
  }

  // Unbounded: an extremely long line with no newline would grow this indefinitely across polls.
  // A real writer never produces such a line; deferred hardening, not fixed here.
  private static byte[] concat(byte[] first, byte[] second) {
    byte[] combined = new byte[first.length + second.length];
    System.arraycopy(first, 0, combined, 0, first.length);
    System.arraycopy(second, 0, combined, first.length, second.length);
    return combined;
  }

  private static int indexOf(byte[] array, int from, byte value) {
    for (int i = from; i < array.length; i++) {
      if (array[i] == value) {
        return i;
      }
    }
    return -1;
  }

  private static RunnerEvent withCanonicalSequence(RunnerEvent raw, long canonicalSequence) {
    return new RunnerEvent(
        raw.schemaVersion(),
        raw.runId(),
        canonicalSequence,
        raw.timestamp(),
        raw.type(),
        raw.runOutcome(),
        raw.testId(),
        raw.testDisplayName(),
        raw.stepId(),
        raw.stepName(),
        raw.detail());
  }

  private static final class RawEventValidationException extends RuntimeException {
    RawEventValidationException(String message) {
      super(message);
    }
  }
}
