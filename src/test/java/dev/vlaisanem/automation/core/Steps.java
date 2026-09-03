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
 * Records named steps within one test as {@code STEP_STARTED}/{@code STEP_PASSED}/{@code
 * STEP_FAILED} {@link RunnerEvent}s, resolved as a JUnit parameter exactly like {@link
 * com.microsoft.playwright.Page} - see {@code AutomationExtension}. Deliberately not a {@code
 * ThreadLocal}/ambient-global design: a test declares {@code Steps} explicitly if it wants
 * step-level reporting, keeping the mechanism visible and directly testable.
 *
 * <p>Every event is appended through {@link RunnerEventWriterRegistry}, the same shared writer
 * {@code RunnerEventTestExecutionListener} uses for this test's own {@code TEST_*} events - both
 * must go through the one writer permitted per runId, so a step's events interleave with its test's
 * own events under one strictly monotonic sequence.
 */
public final class Steps {

  private final String runId;
  private final String testId;
  private final String testDisplayName;
  private final EventSink sink;
  // Identity-keyed (a plain HashMap would use equals()/hashCode(), which two distinct exception
  // instances can share by coincidence - or even the same instance thrown twice would collide with
  // itself as a "duplicate key" under some equals() overrides) and every failed step's own instance
  // is kept, not just the most recent: a test that catches step A's failure, then step B's, then
  // rethrows A's original instance (a plausible negative-test shape - "step B was also expected to
  // fail, but what actually ends the test is A") must still resolve back to step A, not lose the
  // mapping to whichever step failed last.
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
   * Runs {@code action} as one named step with no result: emits {@code STEP_STARTED} before it
   * runs, {@code STEP_PASSED} if it completes normally, or {@code STEP_FAILED} if it throws - then
   * rethrows the original failure unchanged, so the enclosing test still fails exactly as it would
   * without this call. A thin wrapper over {@link #call(String, Supplier)} - see that method for
   * the actual lifecycle (both go through the exact same code, so there is nothing here that could
   * drift out of sync with it).
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
   * Runs {@code action} as one named step, returning its result: emits {@code STEP_STARTED} before
   * it runs, {@code STEP_PASSED} if it completes normally, or {@code STEP_FAILED} if it throws -
   * then rethrows the original failure unchanged, so the enclosing test still fails exactly as it
   * would without this call.
   *
   * <p>If reporting the failure itself throws (a broken writer, a full disk), that reporting
   * failure is attached to the original one as a {@linkplain Throwable#addSuppressed(Throwable)
   * suppressed exception} rather than propagated in its place - a reporting-infrastructure failure
   * must never replace or hide the real assertion/application failure that actually failed the
   * step.
   *
   * <p>If {@code action} succeeds but reporting that success (the {@code STEP_PASSED} write) then
   * throws, {@code result} never reaches the caller at all - a caller of the common shape {@code
   * try (ManagedRoom room = steps.call(...))} never gets as far as assigning {@code room}, so its
   * try-with-resources can never close it. When {@code result} is itself an {@link AutoCloseable}
   * (every {@code Managed*} test resource is), this closes it right here before rethrowing - the
   * only place left that still can - so a resource genuinely created in the SUT is never silently
   * leaked just because reporting the step's own success happened to fail. A close failure is
   * attached as suppressed, the same pattern used for a broken {@code STEP_FAILED} write above.
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
        // ManagedRoom/ManagedBooking/ManagedMessage's own close() throws AssertionError (not
        // Exception) on an unexpected cleanup status - catching only Exception would let that
        // escape this method and replace the reporting failure it was meant to be attached to.
        reportingFailure.addSuppressed(closeFailure);
      }
    }
  }

  /**
   * The id of the step whose action threw exactly {@code executionException} (reference equality,
   * not message/type comparison), or {@code null} if no step's own failure is the one that actually
   * ended the test. Deliberately not just "the most recently failed step": a test that catches a
   * step's failure and later fails for an unrelated reason (a different, unrelated assertion, or
   * even an earlier step's own already-caught instance rethrown later) must not have its failure
   * artifact mis-attributed to the wrong step - every failed step's own instance is remembered (see
   * {@link #stepIdByFailure}), not just the most recent, so only the exact instance JUnit reports
   * via {@code ExtensionContext.getExecutionException()} ever resolves, to whichever step it truly
   * came from.
   */
  public String stepIdForFailure(Throwable executionException) {
    return executionException == null ? null : stepIdByFailure.get(executionException);
  }

  @FunctionalInterface
  interface EventSink {
    void append(LongFunction<RunnerEvent> eventFactory);
  }
}
