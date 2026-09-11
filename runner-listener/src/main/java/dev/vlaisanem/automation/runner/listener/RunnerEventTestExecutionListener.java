package dev.vlaisanem.automation.runner.listener;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.contract.RunnerExecutionIdentity;
import java.time.Instant;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Emits one {@link RunnerEvent} per test-level execution signal JUnit Platform provides (start,
 * finish, skip), through the same {@link RunnerEventWriterRegistry} the {@code Steps} API also
 * writes through. Auto-discovered via {@code META-INF/services/...TestExecutionListener} - no
 * explicit registration needed.
 *
 * <p>Does not emit {@code RUN_STARTED}/{@code RUN_FINISHED}: those are owned by the runner service
 * process, which alone knows the run's terminal outcome, including cancellation/timeout - neither
 * of which reaches a listener inside the JVM being killed.
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
    // A skipped container never calls executionStarted/Skipped for its descendants (JUnit
    // Platform guarantee) - without this, a disabled class's tests would be invisible, not skipped.
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
