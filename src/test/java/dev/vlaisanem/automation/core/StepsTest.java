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

  /** Same broken-writer protection as {@code run}, exercised through {@code call} directly. */
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
   * If reporting a step's success fails, the caller's try-with-resources never assigns its result,
   * so {@code call} must close an {@link AutoCloseable} result itself in that case.
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

  /** A failure closing that resource must be attached as suppressed, not replace the original. */
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
   * A {@code Managed*} resource's close() can throw {@link AssertionError}, not just {@code
   * RuntimeException}; that must also be attached as suppressed, not thrown in place.
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

  /** Proves {@code run} and {@code call} share one correlation map, not two independent ones. */
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
      // Expected - correlation is checked below via stepIdForFailure.
    }
    try {
      steps.call(
          "a call step",
          () -> {
            throw callFailure;
          });
    } catch (RuntimeException caught) {
      // Expected - correlation is checked below via stepIdForFailure.
    }

    assertThat(steps.stepIdForFailure(runFailure)).isNotNull();
    assertThat(steps.stepIdForFailure(callFailure)).isNotNull();
    assertThat(steps.stepIdForFailure(runFailure))
        .isNotEqualTo(steps.stepIdForFailure(callFailure));
  }

  /** If the STEP_FAILED write itself throws, the original failure must still propagate. */
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
   * A later, unrelated failure must not correlate to an earlier step failure that was already
   * caught and handled.
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
      // Expected - the test handles this failure and continues.
    }
    RuntimeException unrelatedFailure = new RuntimeException("a later, unrelated assertion");

    assertThat(steps.stepIdForFailure(unrelatedFailure)).isNull();
    assertThat(steps.stepIdForFailure(stepFailure)).isNotNull();
  }

  /**
   * Correlation must remember every failed step's own instance, not just the most recent: catching
   * step A's failure, then step B's, then rethrowing A's instance must still resolve to A.
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
      // Expected - the test handles step A's failure and continues.
    }
    try {
      steps.run(
          "step B",
          () -> {
            throw failureB;
          });
    } catch (RuntimeException caught) {
      // Expected - the test handles step B's failure too.
    }

    String stepIdA = steps.stepIdForFailure(failureA);
    String stepIdB = steps.stepIdForFailure(failureB);

    assertThat(stepIdA).isNotNull();
    assertThat(stepIdB).isNotNull();
    assertThat(stepIdA).isNotEqualTo(stepIdB);
    // Rethrowing A's original instance last must still resolve to A, not B.
    assertThat(steps.stepIdForFailure(failureA)).isEqualTo(stepIdA);
  }

  private static Steps.EventSink recordingSink(List<RunnerEvent> recorded) {
    long[] sequence = {0};
    return eventFactory -> recorded.add(eventFactory.apply(++sequence[0]));
  }

  /**
   * Stands in for a {@code Managed*} test resource - records whether it was closed. Takes a {@code
   * Runnable} so a test can make {@code close()} throw either a {@code RuntimeException} or an
   * {@code AssertionError}.
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
