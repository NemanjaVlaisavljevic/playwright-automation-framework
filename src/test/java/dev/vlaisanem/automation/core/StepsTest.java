package dev.vlaisanem.automation.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StepsTest {

  private static final String RUN_ID = "run-1";
  private static final String TEST_ID = "test-1";
  private static final String TEST_DISPLAY_NAME = "someTest()";

  @Test
  void aPassingStepEmitsStartedThenPassedAndCorrelatesToNoFailure() {
    List<RunnerEvent> recorded = new ArrayList<>();
    Steps steps = new Steps(RUN_ID, TEST_ID, TEST_DISPLAY_NAME, recordingSink(recorded));

    steps.run("do something", () -> {});

    assertThat(recorded)
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.STEP_STARTED, EventType.STEP_PASSED);
    assertThat(steps.stepIdForFailure(new RuntimeException("unrelated"))).isNull();
  }

  @Test
  void aFailingStepEmitsStartedThenFailedAndRethrowsTheOriginalFailureUnchanged() {
    List<RunnerEvent> recorded = new ArrayList<>();
    Steps steps = new Steps(RUN_ID, TEST_ID, TEST_DISPLAY_NAME, recordingSink(recorded));
    RuntimeException failure = new RuntimeException("boom");

    assertThatThrownBy(
            () ->
                steps.run(
                    "do something",
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);

    assertThat(recorded)
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.STEP_STARTED, EventType.STEP_FAILED);
    assertThat(steps.stepIdForFailure(failure)).isEqualTo(recorded.get(1).stepId());
  }

  @Test
  void aCallingStepReturnsTheActionsResultAndEmitsStartedThenPassed() {
    List<RunnerEvent> recorded = new ArrayList<>();
    Steps steps = new Steps(RUN_ID, TEST_ID, TEST_DISPLAY_NAME, recordingSink(recorded));

    String result = steps.call("do something", () -> "the result");

    assertThat(result).isEqualTo("the result");
    assertThat(recorded)
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.STEP_STARTED, EventType.STEP_PASSED);
  }

  @Test
  void aFailingCallStepEmitsStartedThenFailedAndRethrowsTheOriginalFailureUnchanged() {
    List<RunnerEvent> recorded = new ArrayList<>();
    Steps steps = new Steps(RUN_ID, TEST_ID, TEST_DISPLAY_NAME, recordingSink(recorded));
    RuntimeException failure = new RuntimeException("boom");

    assertThatThrownBy(
            () ->
                steps.call(
                    "do something",
                    () -> {
                      throw failure;
                    }))
        .isSameAs(failure);

    assertThat(recorded)
        .extracting(RunnerEvent::type)
        .containsExactly(EventType.STEP_STARTED, EventType.STEP_FAILED);
    assertThat(steps.stepIdForFailure(failure)).isEqualTo(recorded.get(1).stepId());
  }

  /**
   * Same broken-writer protection as {@code run}, exercised through {@code call} directly - both
   * share the exact same lifecycle (see {@link Steps#run}'s own Javadoc), so this proves that
   * sharing actually holds rather than assuming it from {@code run}'s own coverage alone.
   */
  @Test
  void aBrokenWriterOnCallStepFailedNeverReplacesTheOriginalFailure() {
    RuntimeException originalFailure = new RuntimeException("original assertion failure");
    RuntimeException writerFailure = new RuntimeException("writer exploded");
    Steps.EventSink sink =
        eventFactory -> {
          RunnerEvent event = eventFactory.apply(1);
          if (event.type() == EventType.STEP_FAILED) {
            throw writerFailure;
          }
        };
    Steps steps = new Steps(RUN_ID, TEST_ID, TEST_DISPLAY_NAME, sink);

    assertThatThrownBy(
            () ->
                steps.call(
                    "do something",
                    () -> {
                      throw originalFailure;
                    }))
        .isSameAs(originalFailure)
        .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(writerFailure));
  }

  /**
   * Regression test for a real review finding: a step's action can succeed and return a
   * successfully-created managed resource (a {@code ManagedRoom}/{@code ManagedBooking}/etc.), but
   * if reporting that success then throws, the caller's {@code try (ManagedRoom room =
   * steps.call(...))} never even assigns {@code room} - so its try-with-resources can never close
   * it. {@code call} must close an {@link AutoCloseable} result itself in that case, since it is
   * the only place left that still can.
   */
  @Test
  void aBrokenWriterOnStepPassedClosesAnAutoCloseableResultBeforeRethrowing() {
    RuntimeException writerFailure = new RuntimeException("writer exploded");
    Steps.EventSink sink =
        eventFactory -> {
          RunnerEvent event = eventFactory.apply(1);
          if (event.type() == EventType.STEP_PASSED) {
            throw writerFailure;
          }
        };
    Steps steps = new Steps(RUN_ID, TEST_ID, TEST_DISPLAY_NAME, sink);
    TrackingCloseable resource = new TrackingCloseable(() -> {});

    assertThatThrownBy(() -> steps.call("provision a resource", () -> resource))
        .isSameAs(writerFailure);

    assertThat(resource.closed).isTrue();
  }

  /**
   * If closing that same resource also fails, the close failure must not replace the original
   * reporting failure that actually failed the step - same suppressed-exception pattern already
   * used for a broken {@code STEP_FAILED} write.
   */
  @Test
  void aFailureClosingTheResourceAfterABrokenStepPassedWriteIsAttachedAsSuppressed() {
    RuntimeException writerFailure = new RuntimeException("writer exploded");
    RuntimeException closeFailure = new RuntimeException("cleanup also failed");
    Steps.EventSink sink =
        eventFactory -> {
          RunnerEvent event = eventFactory.apply(1);
          if (event.type() == EventType.STEP_PASSED) {
            throw writerFailure;
          }
        };
    Steps steps = new Steps(RUN_ID, TEST_ID, TEST_DISPLAY_NAME, sink);
    TrackingCloseable resource =
        new TrackingCloseable(
            () -> {
              throw closeFailure;
            });

    assertThatThrownBy(() -> steps.call("provision a resource", () -> resource))
        .isSameAs(writerFailure)
        .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(closeFailure));
  }

  /**
   * Regression test for a real review finding: a real {@code ManagedRoom}/{@code ManagedBooking}/
   * {@code ManagedMessage} throws {@link AssertionError} (not {@code RuntimeException}) on an
   * unexpected cleanup status - catching only {@code Exception} in the cleanup helper would let
   * that {@code AssertionError} escape and replace the reporting failure it was meant to be
   * attached to, contradicting the documented suppressed-exception contract.
   */
  @Test
  void aCloseFailureThatIsAnAssertionErrorIsAlsoAttachedAsSuppressedNotThrownInPlace() {
    RuntimeException writerFailure = new RuntimeException("writer exploded");
    AssertionError closeFailure = new AssertionError("cleanup found an unexpected status");
    Steps.EventSink sink =
        eventFactory -> {
          RunnerEvent event = eventFactory.apply(1);
          if (event.type() == EventType.STEP_PASSED) {
            throw writerFailure;
          }
        };
    Steps steps = new Steps(RUN_ID, TEST_ID, TEST_DISPLAY_NAME, sink);
    TrackingCloseable resource =
        new TrackingCloseable(
            () -> {
              throw closeFailure;
            });

    assertThatThrownBy(() -> steps.call("provision a resource", () -> resource))
        .isSameAs(writerFailure)
        .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(closeFailure));
  }

  /** A result that isn't itself closeable (a plain String, say) must not confuse this cleanup. */
  @Test
  void doesNotAttemptToCloseANonCloseableResultWhenStepPassedWriteFails() {
    RuntimeException writerFailure = new RuntimeException("writer exploded");
    Steps.EventSink sink =
        eventFactory -> {
          RunnerEvent event = eventFactory.apply(1);
          if (event.type() == EventType.STEP_PASSED) {
            throw writerFailure;
          }
        };
    Steps steps = new Steps(RUN_ID, TEST_ID, TEST_DISPLAY_NAME, sink);

    assertThatThrownBy(() -> steps.call("do something", () -> "not closeable"))
        .isSameAs(writerFailure);
  }

  /**
   * Proves {@code run} and {@code call} share one correlation map, not two independent ones - a
   * step run via {@code run} and another via {@code call} in the same test must both resolve
   * correctly.
   */
  @Test
  void correlatesFailuresAcrossBothRunAndCallOnTheSameSteps() {
    List<RunnerEvent> recorded = new ArrayList<>();
    Steps steps = new Steps(RUN_ID, TEST_ID, TEST_DISPLAY_NAME, recordingSink(recorded));
    RuntimeException runFailure = new RuntimeException("run step failed");
    RuntimeException callFailure = new RuntimeException("call step failed");

    try {
      steps.run(
          "a run step",
          () -> {
            throw runFailure;
          });
    } catch (RuntimeException caught) {
      // Deliberately swallowed - see the other correlation tests for why.
    }
    try {
      steps.call(
          "a call step",
          () -> {
            throw callFailure;
          });
    } catch (RuntimeException caught) {
      // Deliberately swallowed too.
    }

    assertThat(steps.stepIdForFailure(runFailure)).isNotNull();
    assertThat(steps.stepIdForFailure(callFailure)).isNotNull();
    assertThat(steps.stepIdForFailure(runFailure))
        .isNotEqualTo(steps.stepIdForFailure(callFailure));
  }

  /**
   * Regression test for a real review finding: if the STEP_FAILED write itself throws (a broken
   * writer, a full disk), the original assertion/application failure must still be what propagates
   * - a reporting-infrastructure failure replacing it would hide the real cause of the test
   * failure.
   */
  @Test
  void aBrokenWriterOnStepFailedNeverReplacesTheOriginalFailure() {
    RuntimeException originalFailure = new RuntimeException("original assertion failure");
    RuntimeException writerFailure = new RuntimeException("writer exploded");
    Steps.EventSink sink =
        eventFactory -> {
          RunnerEvent event = eventFactory.apply(1);
          if (event.type() == EventType.STEP_FAILED) {
            throw writerFailure;
          }
        };
    Steps steps = new Steps(RUN_ID, TEST_ID, TEST_DISPLAY_NAME, sink);

    assertThatThrownBy(
            () ->
                steps.run(
                    "do something",
                    () -> {
                      throw originalFailure;
                    }))
        .isSameAs(originalFailure)
        .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(writerFailure));
  }

  /**
   * Regression test for a real review finding: a test that catches a step's own failure and then
   * fails later for a completely different, unrelated reason must not have its artifact
   * mis-attributed to that earlier, already-handled step - only the exact instance JUnit reports as
   * the test's own execution exception may ever correlate to a step.
   */
  @Test
  void doesNotCorrelateAnUnrelatedLaterFailureToAPreviouslyCaughtStepFailure() {
    List<RunnerEvent> recorded = new ArrayList<>();
    Steps steps = new Steps(RUN_ID, TEST_ID, TEST_DISPLAY_NAME, recordingSink(recorded));
    RuntimeException stepFailure = new RuntimeException("step failure, caught by the test");
    try {
      steps.run(
          "do something",
          () -> {
            throw stepFailure;
          });
    } catch (RuntimeException caught) {
      // Deliberately swallowed here, mirroring a test that expects and handles this step's own
      // failure before going on to fail for a separate reason below.
    }
    RuntimeException unrelatedFailure = new RuntimeException("a later, unrelated assertion");

    assertThat(steps.stepIdForFailure(unrelatedFailure)).isNull();
    assertThat(steps.stepIdForFailure(stepFailure)).isNotNull();
  }

  /**
   * Regression test for a real review finding: correlation must remember every failed step's own
   * instance, not just the most recent one. A test can catch step A's failure, then step B's, then
   * go on to rethrow A's original instance later (e.g. "B was also expected to fail, but what
   * actually ends the test is A") - that must still resolve back to A, not to B or to nothing.
   */
  @Test
  void correlatesEachCaughtStepFailureToItsOwnStepEvenWhenAnEarlierOneIsRethrownLater() {
    List<RunnerEvent> recorded = new ArrayList<>();
    Steps steps = new Steps(RUN_ID, TEST_ID, TEST_DISPLAY_NAME, recordingSink(recorded));
    RuntimeException failureA = new RuntimeException("step A failed");
    RuntimeException failureB = new RuntimeException("step B failed");

    try {
      steps.run(
          "step A",
          () -> {
            throw failureA;
          });
    } catch (RuntimeException caught) {
      // Deliberately swallowed - mirrors a test that expects and handles step A's own failure.
    }
    try {
      steps.run(
          "step B",
          () -> {
            throw failureB;
          });
    } catch (RuntimeException caught) {
      // Deliberately swallowed too - both are "expected" failures at this point.
    }

    String stepIdA = steps.stepIdForFailure(failureA);
    String stepIdB = steps.stepIdForFailure(failureB);

    assertThat(stepIdA).isNotNull();
    assertThat(stepIdB).isNotNull();
    assertThat(stepIdA).isNotEqualTo(stepIdB);
    // Rethrowing A's original instance last must still resolve to A, not to B (the most recent
    // failure) or to nothing.
    assertThat(steps.stepIdForFailure(failureA)).isEqualTo(stepIdA);
  }

  private static Steps.EventSink recordingSink(List<RunnerEvent> recorded) {
    long[] sequence = {0};
    return eventFactory -> recorded.add(eventFactory.apply(++sequence[0]));
  }

  /**
   * Stands in for a {@code Managed*} test resource - records whether it was closed. Takes a plain
   * {@code Runnable} rather than a single exception field so a test can make {@code close()} throw
   * either a {@code RuntimeException} or an {@code AssertionError} (a real {@code ManagedRoom}/
   * {@code ManagedBooking}/{@code ManagedMessage} close failure throws the latter, not the former).
   */
  private static final class TrackingCloseable implements AutoCloseable {
    private final Runnable onClose;
    private boolean closed;

    TrackingCloseable(Runnable onClose) {
      this.onClose = onClose;
    }

    @Override
    public void close() {
      closed = true;
      onClose.run();
    }
  }
}
