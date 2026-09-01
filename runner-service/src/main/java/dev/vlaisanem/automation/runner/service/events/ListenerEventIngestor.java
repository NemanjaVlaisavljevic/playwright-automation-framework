package dev.vlaisanem.automation.runner.service.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
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
 * Tails one run's raw {@code <runId>.tests.jsonl} (written by runner-listener's {@code
 * RunnerEventTestExecutionListener}, a completely separate process) and forwards each validated
 * {@code TEST_*} line into the canonical journal via {@link RunEventAppender}, which assigns it a
 * fresh canonical sequence - the raw file's own source sequence only ever proves the listener's own
 * internal ordering, never the cross-run-lifecycle one a dashboard needs.
 *
 * <p>Polls rather than relying solely on filesystem notifications: {@code WatchService} can
 * coalesce several rapid writes into a single event on Windows, so a poll loop that always
 * re-checks for new bytes is the only mechanism guaranteed not to miss a burst of test events.
 *
 * <p>Runs on its own daemon thread, started at construction and driven to completion by exactly one
 * of two conditions: the raw {@code .tests.complete} marker appears (a clean listener shutdown), or
 * {@link #stopAndAwaitFinished} is called (the process ended without one - cancelled, timed out, or
 * force-killed). Either way, a poll tick that finds nothing new AND observes one of those two
 * conditions performs no further reads - the marker is only ever created after every byte is
 * already flushed, so "nothing new, and complete" is a reliable "there will never be anything new"
 * signal, not a race. A trailing, never-terminated line left behind by an abrupt kill is discarded
 * rather than treated as a validation failure, but only when ingestion was stopped without ever
 * observing the marker - {@link RunService} alone decides whether that missing marker is itself
 * tolerable for the run's own outcome (yes for {@code CANCELLED}/{@code TIMED_OUT}, no otherwise).
 * The marker appearing at all, by contrast, is this class's own unconditional promise that the raw
 * stream is complete and internally consistent: a trailing unterminated line, or a marker with no
 * data file ever created, coexisting with it can only mean corruption, since the real writer always
 * creates the data file first and only creates the marker after closing cleanly - either is a
 * validation failure, never silently tolerated.
 *
 * <p>Backed by a real {@link ExecutorService#submit} - not a bare {@code
 * CompletableFuture.supplyAsync} task, whose {@code cancel(true)} does not actually interrupt the
 * running computation. {@link #stopAndAwaitFinished} depends on a genuine interrupt to promptly
 * break a stuck poll loop out of {@code Thread.sleep} so a hung ingestor can never keep appending
 * events after this method has already given up waiting on it and the caller has moved on to
 * finalizing the run.
 *
 * <p>A malformed line, a source-sequence gap or duplicate, an event for the wrong runId or of a
 * non-{@code TEST_*} type, an unsupported {@code schemaVersion}, or one of the marker-consistency
 * violations above instead stops ingestion immediately with {@link IngestionResult#valid() valid()
 * == false}: once the raw stream's own internal consistency cannot be trusted, forwarding anything
 * further into the canonical journal would just be propagating corruption.
 */
public final class ListenerEventIngestor {

  private static final int MAX_CHUNK_BYTES = 65536;
  private static final byte NEWLINE = (byte) '\n';

  private final String runId;
  private final Path dataFile;
  private final Path completionMarker;
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
      RunEventAppender eventAppender,
      ObjectMapper objectMapper,
      Duration pollInterval) {
    this(
        runId,
        dataFile,
        completionMarker,
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
      RunEventAppender eventAppender,
      ObjectMapper objectMapper,
      Duration pollInterval,
      int maxChunkBytes) {
    this.runId = runId;
    this.dataFile = dataFile;
    this.completionMarker = completionMarker;
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
    this.future = executor.submit(this::runLoop);
  }

  /**
   * Signals the poll loop to finish as soon as it next wakes (at most one {@link #pollInterval}
   * away in the common case) and waits up to {@code timeout} for it to actually do so. Safe to call
   * more than once (e.g. from both {@code cancel()} and the worker thread) or whether or not the
   * raw {@code .tests.complete} marker has appeared yet - {@link Future#get} on an
   * already-completed future simply returns the same result again.
   *
   * <p>On timeout, {@link Future#cancel(boolean) cancel(true)} - backed by a real {@code
   * ExecutorService} task, this genuinely interrupts the poll loop, unlike a bare {@code
   * CompletableFuture} - plus {@link ExecutorService#shutdownNow()} as a second layer, guarantee
   * the background thread cannot outlive this call to later append an event once the caller has
   * already moved on and (most likely) closed this run's canonical journal with {@code
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
      // was still waiting on it - converge on the same kind of terminal result that caller is
      // about to record, rather than letting this exception propagate uncaught.
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
      // The caller's own wait was interrupted, not the ingestion thread - without also stopping
      // the underlying task here, it could keep running (and still append an event) long after
      // this method has already given up and returned.
      interruptAndShutdownNow();
      result =
          IngestionResult.invalid(
              "Interrupted while awaiting listener event ingestion for run " + runId);
    } finally {
      executor.shutdown();
    }
    // First caller to resolve a terminal result wins; every concurrent caller - whatever outcome
    // it individually observed - converges on that same single, stable answer.
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
        boolean stop = stopRequested.get();
        if (newBytes.length == 0 && (complete || stop)) {
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
    if (!event.type().isTestLevel()) {
      throw new RawEventValidationException(
          diagnostic(
              lineNumber,
              lineStartOffset,
              lastSourceSequence + 1,
              event.sequence(),
              "unexpected non-test-level event type " + event.type()));
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
   * Decodes strictly - unlike {@code new String(bytes, UTF_8)}, which silently replaces an invalid
   * byte sequence with U+FFFD, {@link CharsetDecoder} defaults to {@link CodingErrorAction#REPORT}
   * for malformed input and unmappable characters, throwing instead. Invalid UTF-8 in the raw
   * stream is corruption, not something to paper over - the JSON could otherwise stay syntactically
   * valid while a {@code testDisplayName}/{@code detail} silently changes underneath it.
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

  // Deliberately unbounded for now: an extremely long line with no newline would grow this
  // indefinitely across polls. A real writer never produces such a line, so this is deferred
  // hardening (a max-line-length guard), not fixed here.
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
        raw.detail());
  }

  private static final class RawEventValidationException extends RuntimeException {
    RawEventValidationException(String message) {
      super(message);
    }
  }
}
