package dev.vlaisanem.automation.runner.listener;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.contract.RunnerExecutionIdentity;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;

/**
 * The single point of access to this JVM's raw {@code <runId>.tests.jsonl} writer, shared between
 * {@link RunnerEventTestExecutionListener} (test-level events, driven by JUnit Platform's own
 * lifecycle callbacks) and the main automation suite's {@code Steps} API (step-level events,
 * emitted from inside a running test method). {@link RunnerEventJsonlWriter} opens its data file
 * with {@code CREATE_NEW} specifically because at most one writer may ever exist for a given runId
 * in one JVM - constructing a second one for the same runId throws. This registry is what makes
 * that true in practice: it memoizes exactly one {@link RunnerEventJsonlWriter} per runId, so every
 * caller - listener or step - always appends through the same instance and therefore the same
 * atomic sequence counter, however many threads call it concurrently (see {@link
 * RunnerEventJsonlWriter#write}'s own thread-safety contract).
 */
public final class RunnerEventWriterRegistry {

  // Package-private, not private: RunnerEventWriterRegistryTest sets this system property directly
  // to redirect a test run's raw events into a @TempDir instead of the real default directory.
  static final String RAW_EVENTS_DIR_PROPERTY = "runner.rawEventsDir";
  private static final String RAW_EVENTS_DIR_ENV = "RUNNER_RAW_EVENTS_DIR";
  private static final String DEFAULT_RAW_EVENTS_DIR = "build/runner-events/raw";
  // D4.2 - disk protection. Mirrors runner-service's own RunnerProperties#rawEventMaxBytes default
  // (application.yml's D4.2 block) so a dashboard-launched run's producer-side cap agrees with what
  // ListenerEventIngestor is configured to expect - runner-service threads its own configured value
  // down as this exact system property for every run it launches (see SuiteCommandFactory).
  static final String RAW_EVENT_MAX_BYTES_PROPERTY = "runner.rawEventMaxBytes";
  private static final String RAW_EVENT_MAX_BYTES_ENV = "RUNNER_RAW_EVENT_MAX_BYTES";
  private static final long DEFAULT_RAW_EVENT_MAX_BYTES = 2_097_152L;

  private static final ConcurrentHashMap<String, RunnerEventJsonlWriter> WRITERS_BY_RUN_ID =
      new ConcurrentHashMap<>();

  private RunnerEventWriterRegistry() {}

  /**
   * Appends one event to the current run's raw stream, resolving the run's own writer (creating it
   * on first use) and its own {@code runId} the same way {@link RunnerEventTestExecutionListener}
   * always has - see {@link RunnerExecutionIdentity#currentRunId()}.
   */
  public static void appendForCurrentRun(LongFunction<RunnerEvent> eventFactory) {
    writerFor(RunnerExecutionIdentity.currentRunId()).write(eventFactory);
  }

  /**
   * Package-private: {@link RunnerEventTestExecutionListener} is the only caller with a legitimate
   * reason to close this run's writer, from its own {@code testPlanExecutionFinished} callback -
   * closing it from anywhere else could cut off in-flight {@code Steps} calls still running inside
   * a test method.
   */
  static RunnerEventJsonlWriter writerFor(String runId) {
    return WRITERS_BY_RUN_ID.computeIfAbsent(runId, RunnerEventWriterRegistry::createWriter);
  }

  static void closeCurrentRun(String runId) {
    RunnerEventJsonlWriter writer = WRITERS_BY_RUN_ID.remove(runId);
    if (writer != null) {
      writer.close();
    }
  }

  private static RunnerEventJsonlWriter createWriter(String runId) {
    Path rawEventsDir = resolveRawEventsDir();
    return new RunnerEventJsonlWriter(
        rawEventsDir.resolve(runId + ".tests.jsonl"),
        rawEventsDir.resolve(runId + ".tests.complete"),
        rawEventsDir.resolve(runId + ".tests.overflow"),
        resolveRawEventMaxBytes(),
        RunnerEventObjectMapper.create());
  }

  private static Path resolveRawEventsDir() {
    return Path.of(setting(RAW_EVENTS_DIR_PROPERTY, RAW_EVENTS_DIR_ENV, DEFAULT_RAW_EVENTS_DIR));
  }

  private static final long MIN_RAW_EVENT_MAX_BYTES = 1024L;

  private static long resolveRawEventMaxBytes() {
    String value =
        setting(
            RAW_EVENT_MAX_BYTES_PROPERTY,
            RAW_EVENT_MAX_BYTES_ENV,
            Long.toString(DEFAULT_RAW_EVENT_MAX_BYTES));
    long maxBytes;
    try {
      maxBytes = Long.parseLong(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          RAW_EVENT_MAX_BYTES_PROPERTY + " must be a whole number, but was '" + value + "'",
          exception);
    }
    // A non-positive (or implausibly tiny) value would make the very first event overflow
    // immediately - fail closed at resolution time with a clear cause, rather than silently
    // producing a run that always reports ERROR with no obvious reason why.
    if (maxBytes < MIN_RAW_EVENT_MAX_BYTES) {
      throw new IllegalArgumentException(
          RAW_EVENT_MAX_BYTES_PROPERTY
              + " must be at least "
              + MIN_RAW_EVENT_MAX_BYTES
              + ", but was "
              + maxBytes);
    }
    return maxBytes;
  }

  private static String setting(String property, String environment, String fallback) {
    String systemValue = System.getProperty(property);
    if (systemValue != null && !systemValue.isBlank()) {
      return systemValue.trim();
    }
    String environmentValue = System.getenv(environment);
    return environmentValue == null || environmentValue.isBlank()
        ? fallback
        : environmentValue.trim();
  }
}
