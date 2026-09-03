package dev.vlaisanem.automation.runner.listener;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.contract.RunnerExecutionIdentity;
import java.time.Instant;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Emits one {@link RunnerEvent} per test-level execution signal JUnit Platform already provides
 * (start, finish, skip) as JSON Lines, through the same {@link RunnerEventWriterRegistry} the main
 * suite's {@code Steps} API also writes through - see that class for why there can only ever be one
 * writer per runId. Auto-discovered by JUnit Platform via {@code
 * META-INF/services/org.junit.platform.launcher.TestExecutionListener} whenever this module is on a
 * test's runtime classpath - no explicit registration needed in the consuming build.
 *
 * <p>Deliberately does not emit {@code RUN_STARTED}/{@code RUN_FINISHED}: those are owned by the
 * runner service process that launches this JVM, which alone knows the run's terminal {@code
 * RunOutcome} - including cancellation or a timeout, neither of which ever reaches a listener
 * running inside the JVM being killed.
 */
public final class RunnerEventTestExecutionListener implements TestExecutionListener {

  private final String runId;
  private volatile TestPlan testPlan;

  public RunnerEventTestExecutionListener() {
    this(RunnerExecutionIdentity.currentRunId());
  }

  RunnerEventTestExecutionListener(String runId) {
    this.runId = runId;
  }

  @Override
  public void executionStarted(TestIdentifier testIdentifier) {
    if (!testIdentifier.isTest()) {
      return;
    }
    String testId = testIdentifier.getUniqueId();
    String displayName = testIdentifier.getDisplayName();
    Instant now = Instant.now();
    RunnerEventWriterRegistry.writerFor(runId)
        .write(seq -> RunnerEvent.testStarted(runId, seq, now, testId, displayName));
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
    RunnerEventWriterRegistry.writerFor(runId)
        .write(seq -> RunnerEvent.testSkipped(runId, seq, now, testId, displayName, reason));
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
    String failureMessage =
        testExecutionResult.getThrowable().map(FailureDetailFormatter::format).orElse(null);
    TestExecutionResult.Status status = testExecutionResult.getStatus();
    RunnerEventWriterRegistry.writerFor(runId)
        .write(
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
    RunnerEventWriterRegistry.closeCurrentRun(runId);
  }
}
