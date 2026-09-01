package dev.vlaisanem.automation.runner.listener;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Emits one {@link RunnerEvent} per test-level execution signal JUnit Platform already provides
 * (start, finish, skip) as JSON Lines. Auto-discovered by JUnit Platform via {@code
 * META-INF/services/org.junit.platform.launcher.TestExecutionListener} whenever this module is on a
 * test's runtime classpath - no explicit registration needed in the consuming build.
 *
 * <p>Deliberately does not emit {@code RUN_STARTED}/{@code RUN_FINISHED}: those are owned by the
 * runner service process that launches this JVM, which alone knows the run's terminal {@code
 * RunOutcome} - including cancellation or a timeout, neither of which ever reaches a listener
 * running inside the JVM being killed.
 */
public final class RunnerEventTestExecutionListener implements TestExecutionListener {

  private static final String RUN_ID_PROPERTY = "runner.runId";
  private static final String RUN_ID_ENV = "RUNNER_RUN_ID";
  private static final String RAW_EVENTS_DIR_PROPERTY = "runner.rawEventsDir";
  private static final String RAW_EVENTS_DIR_ENV = "RUNNER_RAW_EVENTS_DIR";
  private static final String DEFAULT_RAW_EVENTS_DIR = "build/runner-events/raw";

  private final String runId;
  private final RunnerEventJsonlWriter writer;
  private volatile TestPlan testPlan;

  public RunnerEventTestExecutionListener() {
    this(resolveRunId(), resolveRawEventsDir());
  }

  /**
   * {@code rawEventsDir} is deliberately a separate concept from the runner service's own canonical
   * event journal: this listener only ever sees test-level execution signals from inside the JVM
   * being run, has no notion of cancellation/timeout/degradation, and cannot itself assign the
   * cross-run-lifecycle sequence numbers a dashboard needs - see {@code RunnerEvent}'s own Javadoc.
   * {@code <runId>.tests.jsonl}/{@code .tests.complete} here are raw, listener-owned artifacts; the
   * runner service ingests them into its own {@code <runId>.events.jsonl} canonical journal.
   */
  RunnerEventTestExecutionListener(String runId, Path rawEventsDir) {
    this.runId = runId;
    this.writer =
        new RunnerEventJsonlWriter(
            rawEventsDir.resolve(runId + ".tests.jsonl"),
            rawEventsDir.resolve(runId + ".tests.complete"),
            RunnerEventObjectMapper.create());
  }

  @Override
  public void executionStarted(TestIdentifier testIdentifier) {
    if (!testIdentifier.isTest()) {
      return;
    }
    String testId = testIdentifier.getUniqueId();
    String displayName = testIdentifier.getDisplayName();
    Instant now = Instant.now();
    writer.write(seq -> RunnerEvent.testStarted(runId, seq, now, testId, displayName));
  }

  @Override
  public void executionSkipped(TestIdentifier testIdentifier, String reason) {
    if (testIdentifier.isTest()) {
      writeSkipped(testIdentifier, reason);
      return;
    }
    // A skipped container (e.g. a class-level @Disabled) never calls executionStarted/Skipped for
    // its descendants - JUnit Platform guarantees that explicitly. Without this, every test method
    // in a disabled class would be invisible in the event log instead of showing up as skipped.
    if (testPlan != null) {
      testPlan.getDescendants(testIdentifier).stream()
          .filter(TestIdentifier::isTest)
          .forEach(descendant -> writeSkipped(descendant, reason));
    }
  }

  private void writeSkipped(TestIdentifier testIdentifier, String reason) {
    String testId = testIdentifier.getUniqueId();
    String displayName = testIdentifier.getDisplayName();
    Instant now = Instant.now();
    writer.write(seq -> RunnerEvent.testSkipped(runId, seq, now, testId, displayName, reason));
  }

  @Override
  public void testPlanExecutionStarted(TestPlan testPlan) {
    this.testPlan = testPlan;
  }

  @Override
  public void executionFinished(
      TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
    if (!testIdentifier.isTest()) {
      return;
    }
    String testId = testIdentifier.getUniqueId();
    String displayName = testIdentifier.getDisplayName();
    Instant now = Instant.now();
    String failureMessage = testExecutionResult.getThrowable().map(this::describe).orElse(null);
    TestExecutionResult.Status status = testExecutionResult.getStatus();
    writer.write(
        seq ->
            switch (status) {
              case SUCCESSFUL -> RunnerEvent.testPassed(runId, seq, now, testId, displayName);
              case FAILED ->
                  RunnerEvent.testFailed(runId, seq, now, testId, displayName, failureMessage);
              case ABORTED ->
                  RunnerEvent.testAborted(runId, seq, now, testId, displayName, failureMessage);
            });
  }

  @Override
  public void testPlanExecutionFinished(TestPlan testPlan) {
    writer.close();
  }

  private String describe(Throwable throwable) {
    String message = throwable.getMessage();
    return message == null || message.isBlank() ? throwable.getClass().getName() : message;
  }

  private static String resolveRunId() {
    String configured = setting(RUN_ID_PROPERTY, RUN_ID_ENV, null);
    return configured != null ? configured : "local-" + UUID.randomUUID();
  }

  private static Path resolveRawEventsDir() {
    return Path.of(setting(RAW_EVENTS_DIR_PROPERTY, RAW_EVENTS_DIR_ENV, DEFAULT_RAW_EVENTS_DIR));
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
