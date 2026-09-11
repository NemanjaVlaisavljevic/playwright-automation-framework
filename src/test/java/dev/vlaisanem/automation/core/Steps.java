package dev.vlaisanem.automation.core;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.listener.FailureDetailFormatter;
import dev.vlaisanem.automation.runner.listener.RunnerEventWriterRegistry;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/**
 * Records named steps within a test as {@code STEP_STARTED}/{@code STEP_PASSED}/{@code STEP_FAILED}
 * {@link RunnerEvent}s, resolved as a JUnit parameter like {@link com.microsoft.playwright.Page}.
 * Shares one {@link RunnerEventWriterRegistry} writer per runId with the test's own {@code TEST_*}
 * events so both stay in a single monotonic sequence.
 */
public final class Steps {

  private final String runId;
  private final String testId;
  private final String testDisplayName;
  private final EventSink sink;
  // Identity-keyed (not equals()/hashCode()) and keeps every failed step's instance, not just the
  // most recent, so a later rethrow of an earlier step's exception still resolves to that step.
  private final Map<Throwable, String> stepIdByFailure =
      Collections.synchronizedMap(new IdentityHashMap<>());

  Steps(String runId, String testId, String testDisplayName) {
    this(runId, testId, testDisplayName, RunnerEventWriterRegistry::appendForCurrentRun);
  }

  /** Test-only entry point: lets a test inject a sink that can observe or fail on demand. */
  Steps(String runId, String testId, String testDisplayName, EventSink sink) {
    this.runId = runId;
    this.testId = testId;
    this.testDisplayName = testDisplayName;
    this.sink = sink;
  }

  /**
   * Runs {@code action} as one named step with no result, delegating to {@link #call(String,
   * Supplier)} for the actual lifecycle.
   */
  public void run(String name, Runnable action) {
    call(
        name,
        () -> {
          action.run();
          return null;
        });
  }

  /**
   * Runs {@code action} as one named step, returning its result, and rethrows any failure
   * unchanged; a failure while reporting it is attached as a {@linkplain
   * Throwable#addSuppressed(Throwable) suppressed exception} rather than replacing it. If {@code
   * action} succeeds but reporting success then throws, a {@code result} that is itself {@link
   * AutoCloseable} is closed here before rethrowing, since it would otherwise never reach the
   * caller to be closed.
   */
  public <T> T call(String name, Supplier<T> action) {
    String stepId = UUID.randomUUID().toString();
    sink.append(
        seq ->
            RunnerEvent.stepStarted(
                runId, seq, Instant.now(), testId, testDisplayName, stepId, name));
    T result;
    try {
      result = action.get();
    } catch (RuntimeException | Error failure) {
      stepIdByFailure.put(failure, stepId);
      String detail = FailureDetailFormatter.format(failure);
      try {
        sink.append(
            seq ->
                RunnerEvent.stepFailed(
                    runId, seq, Instant.now(), testId, testDisplayName, stepId, name, detail));
      } catch (RuntimeException reportingFailure) {
        failure.addSuppressed(reportingFailure);
      }
      throw failure;
    }
    try {
      sink.append(
          seq ->
              RunnerEvent.stepPassed(
                  runId, seq, Instant.now(), testId, testDisplayName, stepId, name));
    } catch (RuntimeException reportingFailure) {
      closeIfAutoCloseable(result, reportingFailure);
      throw reportingFailure;
    }
    return result;
  }

  private static void closeIfAutoCloseable(Object result, RuntimeException reportingFailure) {
    if (result instanceof AutoCloseable closeable) {
      try {
        closeable.close();
      } catch (Exception | AssertionError closeFailure) {
        // Managed* resources throw AssertionError (not Exception) on unexpected cleanup status.
        reportingFailure.addSuppressed(closeFailure);
      }
    }
  }

  /**
   * The id of the step whose action threw exactly {@code executionException} (reference equality),
   * or {@code null} if none did. Matches by identity, not by "most recently failed," since a caught
   * step failure can be rethrown after a later, different step has also failed.
   */
  public String stepIdForFailure(Throwable executionException) {
    return executionException == null ? null : stepIdByFailure.get(executionException);
  }

  @FunctionalInterface
  interface EventSink {
    void append(LongFunction<RunnerEvent> eventFactory);
  }
}
