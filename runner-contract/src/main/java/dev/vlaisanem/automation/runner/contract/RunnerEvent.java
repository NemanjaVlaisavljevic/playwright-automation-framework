package dev.vlaisanem.automation.runner.contract;

import java.time.Instant;
import java.util.Objects;

/**
 * Event envelope shared between the JUnit listener, the {@code Steps} API, and the runner service,
 * schema version 1.1. The runner service owns run-level lifecycle events; the listener owns
 * test-level execution events; the {@code Steps} API (main automation suite) owns step-level
 * events. The contract is deliberately framework-agnostic (no serialization annotations), so each
 * side configures its own JSON mapper.
 *
 * @param runOutcome terminal process outcome; required for {@link EventType#RUN_FINISHED} and
 *     absent for every other event type.
 * @param testId JUnit's {@code TestIdentifier.getUniqueId()}; required for test-level and
 *     step-level types, absent for every run-level type ({@link EventType#RUN_QUEUED}, {@link
 *     EventType#RUN_STARTED}, {@link EventType#RUN_FINISHED}).
 * @param testDisplayName {@code TestIdentifier.getDisplayName()}; required for test-level and
 *     step-level types, absent for run-level types.
 * @param stepId opaque identifier for one step within a test, scoped to that test; required for
 *     step-level types, absent for every other event type.
 * @param stepName human-readable step name; required for step-level types, absent for every other
 *     event type.
 * @param detail failure message, skip reason, or {@code null} when not applicable.
 */
public record RunnerEvent(
    String schemaVersion,
    String runId,
    long sequence,
    Instant timestamp,
    EventType type,
    RunOutcome runOutcome,
    String testId,
    String testDisplayName,
    String stepId,
    String stepName,
    String detail) {

  public static final String CURRENT_SCHEMA_VERSION = "1.1";

  public RunnerEvent {
    if (schemaVersion == null || schemaVersion.isBlank()) {
      throw new IllegalArgumentException("schemaVersion must not be blank");
    }
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("runId must not be blank");
    }
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive, was " + sequence);
    }
    Objects.requireNonNull(timestamp, "timestamp must not be null");
    Objects.requireNonNull(type, "type must not be null");
    if (type == EventType.RUN_FINISHED) {
      Objects.requireNonNull(runOutcome, "RUN_FINISHED requires a runOutcome");
    } else if (runOutcome != null) {
      throw new IllegalArgumentException(type + " must not carry a runOutcome");
    }
    if (type.isStepLevel()) {
      if (testId == null || testId.isBlank()) {
        throw new IllegalArgumentException(type + " requires a non-blank testId");
      }
      if (testDisplayName == null || testDisplayName.isBlank()) {
        throw new IllegalArgumentException(type + " requires a non-blank testDisplayName");
      }
      if (stepId == null || stepId.isBlank()) {
        throw new IllegalArgumentException(type + " requires a non-blank stepId");
      }
      if (stepName == null || stepName.isBlank()) {
        throw new IllegalArgumentException(type + " requires a non-blank stepName");
      }
    } else if (type.isTestLevel()) {
      if (testId == null || testId.isBlank()) {
        throw new IllegalArgumentException(type + " requires a non-blank testId");
      }
      if (testDisplayName == null || testDisplayName.isBlank()) {
        throw new IllegalArgumentException(type + " requires a non-blank testDisplayName");
      }
      if (stepId != null || stepName != null) {
        throw new IllegalArgumentException(type + " must not carry a step identifier");
      }
    } else {
      if (testId != null || testDisplayName != null) {
        throw new IllegalArgumentException(type + " must not carry a test identifier");
      }
      if (stepId != null || stepName != null) {
        throw new IllegalArgumentException(type + " must not carry a step identifier");
      }
    }
  }

  public static RunnerEvent runQueued(String runId, long sequence, Instant timestamp) {
    return new RunnerEvent(
        CURRENT_SCHEMA_VERSION,
        runId,
        sequence,
        timestamp,
        EventType.RUN_QUEUED,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static RunnerEvent runStarted(String runId, long sequence, Instant timestamp) {
    return new RunnerEvent(
        CURRENT_SCHEMA_VERSION,
        runId,
        sequence,
        timestamp,
        EventType.RUN_STARTED,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static RunnerEvent runFinished(
      String runId, long sequence, Instant timestamp, RunOutcome runOutcome, String detail) {
    return new RunnerEvent(
        CURRENT_SCHEMA_VERSION,
        runId,
        sequence,
        timestamp,
        EventType.RUN_FINISHED,
        runOutcome,
        null,
        null,
        null,
        null,
        detail);
  }

  public static RunnerEvent testStarted(
      String runId, long sequence, Instant timestamp, String testId, String testDisplayName) {
    return new RunnerEvent(
        CURRENT_SCHEMA_VERSION,
        runId,
        sequence,
        timestamp,
        EventType.TEST_STARTED,
        null,
        testId,
        testDisplayName,
        null,
        null,
        null);
  }

  public static RunnerEvent testPassed(
      String runId, long sequence, Instant timestamp, String testId, String testDisplayName) {
    return new RunnerEvent(
        CURRENT_SCHEMA_VERSION,
        runId,
        sequence,
        timestamp,
        EventType.TEST_PASSED,
        null,
        testId,
        testDisplayName,
        null,
        null,
        null);
  }

  public static RunnerEvent testFailed(
      String runId,
      long sequence,
      Instant timestamp,
      String testId,
      String testDisplayName,
      String failureMessage) {
    return new RunnerEvent(
        CURRENT_SCHEMA_VERSION,
        runId,
        sequence,
        timestamp,
        EventType.TEST_FAILED,
        null,
        testId,
        testDisplayName,
        null,
        null,
        failureMessage);
  }

  public static RunnerEvent testAborted(
      String runId,
      long sequence,
      Instant timestamp,
      String testId,
      String testDisplayName,
      String reason) {
    return new RunnerEvent(
        CURRENT_SCHEMA_VERSION,
        runId,
        sequence,
        timestamp,
        EventType.TEST_ABORTED,
        null,
        testId,
        testDisplayName,
        null,
        null,
        reason);
  }

  public static RunnerEvent testSkipped(
      String runId,
      long sequence,
      Instant timestamp,
      String testId,
      String testDisplayName,
      String reason) {
    return new RunnerEvent(
        CURRENT_SCHEMA_VERSION,
        runId,
        sequence,
        timestamp,
        EventType.TEST_SKIPPED,
        null,
        testId,
        testDisplayName,
        null,
        null,
        reason);
  }

  public static RunnerEvent stepStarted(
      String runId,
      long sequence,
      Instant timestamp,
      String testId,
      String testDisplayName,
      String stepId,
      String stepName) {
    return new RunnerEvent(
        CURRENT_SCHEMA_VERSION,
        runId,
        sequence,
        timestamp,
        EventType.STEP_STARTED,
        null,
        testId,
        testDisplayName,
        stepId,
        stepName,
        null);
  }

  public static RunnerEvent stepPassed(
      String runId,
      long sequence,
      Instant timestamp,
      String testId,
      String testDisplayName,
      String stepId,
      String stepName) {
    return new RunnerEvent(
        CURRENT_SCHEMA_VERSION,
        runId,
        sequence,
        timestamp,
        EventType.STEP_PASSED,
        null,
        testId,
        testDisplayName,
        stepId,
        stepName,
        null);
  }

  public static RunnerEvent stepFailed(
      String runId,
      long sequence,
      Instant timestamp,
      String testId,
      String testDisplayName,
      String stepId,
      String stepName,
      String failureMessage) {
    return new RunnerEvent(
        CURRENT_SCHEMA_VERSION,
        runId,
        sequence,
        timestamp,
        EventType.STEP_FAILED,
        null,
        testId,
        testDisplayName,
        stepId,
        stepName,
        failureMessage);
  }
}
