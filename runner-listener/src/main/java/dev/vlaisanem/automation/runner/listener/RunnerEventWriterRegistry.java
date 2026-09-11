package dev.vlaisanem.automation.runner.listener;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.contract.RunnerExecutionIdentity;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;

/**
 * The single point of access to this JVM's raw {@code <runId>.tests.jsonl} writer, shared between
 * {@link RunnerEventTestExecutionListener} (test-level events) and the {@code Steps} API
 * (step-level events). Memoizes exactly one {@link RunnerEventJsonlWriter} per runId, since {@code
 * CREATE_NEW} makes constructing a second one for the same runId throw - every caller therefore
 * appends through the same instance and the same atomic sequence counter.
 */
public final class RunnerEventWriterRegistry {

  // Package-private: RunnerEventWriterRegistryTest sets this directly to redirect raw events into
  // a @TempDir.
  static final String RAW_EVENTS_DIR_PROPERTY = "runner.rawEventsDir";
  private static final String RAW_EVENTS_DIR_ENV = "RUNNER_RAW_EVENTS_DIR";
  private static final String DEFAULT_RAW_EVENTS_DIR = "build/runner-events/raw";
  // Mirrors runner-service's RunnerProperties#rawEventMaxBytes default so a dashboard-launched
  // run's cap agrees with what ListenerEventIngestor expects (threaded down via
  // SuiteCommandFactory).
  static final String RAW_EVENT_MAX_BYTES_PROPERTY = "runner.rawEventMaxBytes";
  private static final String RAW_EVENT_MAX_BYTES_ENV = "RUNNER_RAW_EVENT_MAX_BYTES";
  private static final long DEFAULT_RAW_EVENT_MAX_BYTES = 2_097_152L;

  private static final ConcurrentHashMap<String, RunnerEventJsonlWriter> WRITERS_BY_RUN_ID =
      new ConcurrentHashMap<>();

  private RunnerEventWriterRegistry() {}

  /** Appends one event to the current run's raw stream, creating its writer on first use. */
  public static void appendForCurrentRun(LongFunction<RunnerEvent> eventFactory) {
    writerFor(RunnerExecutionIdentity.currentRunId()).write(eventFactory);
  }

  /**
   * {@link RunnerEventTestExecutionListener} is the only caller that should close this run's
   * writer, from {@code testPlanExecutionFinished} - closing it elsewhere could cut off in-flight
   * {@code Steps} calls still running inside a test method.
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
    // Fail closed at resolution time: a non-positive/tiny value would overflow on the very first
    // event, producing an ERROR run with no obvious cause.
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
