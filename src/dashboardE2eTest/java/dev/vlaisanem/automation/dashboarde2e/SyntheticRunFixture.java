package dev.vlaisanem.automation.dashboarde2e;

import dev.vlaisanem.automation.runner.contract.RunnerEvent;

/**
 * Builds a synthetic SSE stream body for a run with an arbitrary number of tests and steps per test
 * - shared by {@code LargeRunE2eTest} (C4.6.4, correctness at scale) and {@code
 * RenderPerformanceE2eTest} (C4.6.5, render/filter timing), both of which need the exact same "~100
 * tests, hundreds of steps" scenario. Every 10th test (1-indexed) fails on its last step, the rest
 * pass in full - a deterministic, reproducible mix of statuses rather than a random one, so a
 * failing assertion always points at the same reproducible scenario.
 */
final class SyntheticRunFixture {

  private SyntheticRunFixture() {}

  static String testDisplayName(int testNumber) {
    return "synthetic test %03d - verifies scenario #%d behaves correctly end to end"
        .formatted(testNumber, testNumber);
  }

  static String stepName(int testNumber, int stepNumber) {
    return "synthetic step %d for test %03d".formatted(stepNumber, testNumber);
  }

  static boolean testFails(int testNumber) {
    return testNumber % 10 == 0;
  }

  static String streamBody(String runId, int testCount, int stepsPerTest) {
    StringBuilder body = new StringBuilder();
    int sequence = 1;
    body.append(
        sseFrame(sequence++, "RUN_QUEUED", runLevelJson(runId, sequence - 1, "RUN_QUEUED")));
    body.append(
        sseFrame(sequence++, "RUN_STARTED", runLevelJson(runId, sequence - 1, "RUN_STARTED")));

    for (int t = 1; t <= testCount; t++) {
      String testId = "synthetic-test-%03d".formatted(t);
      String testName = testDisplayName(t);
      boolean fails = testFails(t);

      body.append(
          sseFrame(
              sequence++,
              "TEST_STARTED",
              testLevelJson(runId, sequence - 1, "TEST_STARTED", testId, testName, null)));

      for (int s = 1; s <= stepsPerTest; s++) {
        String stepId = "synthetic-step-%03d-%d".formatted(t, s);
        String stepName = stepName(t, s);
        boolean lastStep = s == stepsPerTest;
        body.append(
            sseFrame(
                sequence++,
                "STEP_STARTED",
                stepLevelJson(
                    runId,
                    sequence - 1,
                    "STEP_STARTED",
                    testId,
                    testName,
                    stepId,
                    stepName,
                    null)));

        boolean thisStepFails = fails && lastStep;
        String stepOutcome = thisStepFails ? "STEP_FAILED" : "STEP_PASSED";
        String detail = thisStepFails ? "synthetic failure for test %03d".formatted(t) : null;
        body.append(
            sseFrame(
                sequence++,
                stepOutcome,
                stepLevelJson(
                    runId, sequence - 1, stepOutcome, testId, testName, stepId, stepName, detail)));
      }

      String testOutcome = fails ? "TEST_FAILED" : "TEST_PASSED";
      String testDetail = fails ? "synthetic failure for test %03d".formatted(t) : null;
      body.append(
          sseFrame(
              sequence++,
              testOutcome,
              testLevelJson(runId, sequence - 1, testOutcome, testId, testName, testDetail)));
    }

    body.append(sseFrame(sequence, "RUN_FINISHED", runFinishedJson(runId, sequence)));
    return body.toString();
  }

  private static String sseFrame(int sequence, String type, String json) {
    return "id:" + sequence + "\n" + "event:" + type + "\n" + "data:" + json + "\n\n";
  }

  private static String runLevelJson(String runId, int sequence, String type) {
    return """
        {"schemaVersion":"%s","runId":"%s","sequence":%d,"timestamp":"2026-09-04T10:00:00Z","type":"%s"}"""
        .formatted(RunnerEvent.CURRENT_SCHEMA_VERSION, runId, sequence, type);
  }

  private static String testLevelJson(
      String runId,
      int sequence,
      String type,
      String testId,
      String testDisplayName,
      String detail) {
    String detailField = detail == null ? "" : ",\"detail\":\"%s\"".formatted(jsonEscape(detail));
    return """
        {"schemaVersion":"%s","runId":"%s","sequence":%d,"timestamp":"2026-09-04T10:00:01Z","type":"%s","testId":"%s","testDisplayName":"%s"%s}"""
        .formatted(
            RunnerEvent.CURRENT_SCHEMA_VERSION,
            runId,
            sequence,
            type,
            testId,
            jsonEscape(testDisplayName),
            detailField);
  }

  private static String stepLevelJson(
      String runId,
      int sequence,
      String type,
      String testId,
      String testDisplayName,
      String stepId,
      String stepName,
      String detail) {
    String detailField = detail == null ? "" : ",\"detail\":\"%s\"".formatted(jsonEscape(detail));
    return """
        {"schemaVersion":"%s","runId":"%s","sequence":%d,"timestamp":"2026-09-04T10:00:02Z","type":"%s","testId":"%s","testDisplayName":"%s","stepId":"%s","stepName":"%s"%s}"""
        .formatted(
            RunnerEvent.CURRENT_SCHEMA_VERSION,
            runId,
            sequence,
            type,
            testId,
            jsonEscape(testDisplayName),
            stepId,
            jsonEscape(stepName),
            detailField);
  }

  private static String runFinishedJson(String runId, int sequence) {
    return """
        {"schemaVersion":"%s","runId":"%s","sequence":%d,"timestamp":"2026-09-04T10:00:03Z","type":"RUN_FINISHED","runOutcome":"FAILED"}"""
        .formatted(RunnerEvent.CURRENT_SCHEMA_VERSION, runId, sequence);
  }

  private static String jsonEscape(String raw) {
    return raw.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\t", "\\t");
  }
}
