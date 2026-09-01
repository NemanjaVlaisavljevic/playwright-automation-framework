package dev.vlaisanem.automation.runner.contract;

import java.time.Instant;
import java.util.Objects;

/**
 * V1 event envelope shared between the JUnit listener and runner service. The runner service owns
 * run-level lifecycle events; the listener owns test-level execution events. The contract is
 * deliberately framework-agnostic (no serialization annotations), so each side configures its own
 * JSON mapper.
 *
 * @param runOutcome terminal process outcome; required for {@link EventType#RUN_FINISHED} and
 *     absent for every other event type.
 * @param testId JUnit's {@code TestIdentifier.getUniqueId()}; required for test-level types, absent
 *     for every run-level type ({@link EventType#RUN_QUEUED}, {@link EventType#RUN_STARTED}, {@link
 *     EventType#RUN_FINISHED}).
 * @param testDisplayName {@code TestIdentifier.getDisplayName()}; required for test-level types and
 *     absent for run-level types.
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
    String detail) {

  public static final String CURRENT_SCHEMA_VERSION = "1.0";

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
    if (type.isTestLevel()) {
      if (testId == null || testId.isBlank()) {
        throw new IllegalArgumentException(type + " requires a non-blank testId");
      }
      if (testDisplayName == null || testDisplayName.isBlank()) {
        throw new IllegalArgumentException(type + " requires a non-blank testDisplayName");
      }
    } else if (testId != null || testDisplayName != null) {
      throw new IllegalArgumentException(type + " must not carry a test identifier");
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
        reason);
  }
}
