import { describe, expect, it } from "vitest";
import type { DisplayStep, DisplayTest } from "./run-details-view-model";
import {
  DEFAULT_TEST_RESULTS_FILTER,
  filterTestResults,
  type TestResultsFilter,
} from "./test-results-filter";

function displayStep(overrides: Partial<DisplayStep> = {}): DisplayStep {
  return {
    stepId: "step-1",
    stepName: "do something",
    status: "PASSED",
    firstSequence: 1,
    interrupted: false,
    artifacts: [],
    ...overrides,
  };
}

function displayTest(overrides: Partial<DisplayTest> = {}): DisplayTest {
  return {
    testId: "test-1",
    testDisplayName: "aTest()",
    status: "PASSED",
    firstSequence: 1,
    interrupted: false,
    steps: [],
    hasArtifacts: false,
    ...overrides,
  };
}

function filter(overrides: Partial<TestResultsFilter> = {}): TestResultsFilter {
  return { ...DEFAULT_TEST_RESULTS_FILTER, ...overrides };
}

describe("filterTestResults", () => {
  it("with an empty filter, returns every test in its original order", () => {
    const tests = [
      displayTest({ testId: "test-b", firstSequence: 2 }),
      displayTest({ testId: "test-a", firstSequence: 1 }),
    ];

    const results = filterTestResults(tests, DEFAULT_TEST_RESULTS_FILTER);

    expect(results.map((r) => r.test.testId)).toEqual(["test-b", "test-a"]);
  });

  it("matches by testDisplayName", () => {
    const tests = [
      displayTest({ testId: "t-1", testDisplayName: "loginTest()" }),
      displayTest({ testId: "t-2", testDisplayName: "logoutTest()" }),
      displayTest({ testId: "t-3", testDisplayName: "bookingTest()" }),
    ];

    const results = filterTestResults(tests, filter({ search: "login" }));

    expect(results.map((r) => r.test.testId)).toEqual(["t-1"]);
  });

  it("matches by the real JUnit unique testId, not just the display name", () => {
    const junitTestId =
      "[engine:junit-jupiter]/[class:BookingJourneyTest]/[method:verifiesBooking()]";
    const tests = [
      displayTest({
        testId: junitTestId,
        testDisplayName: "verifiesBooking()",
      }),
      displayTest({ testId: "other", testDisplayName: "unrelated()" }),
    ];

    const results = filterTestResults(
      tests,
      filter({ search: "BookingJourneyTest" }),
    );

    expect(results.map((r) => r.test.testId)).toEqual([junitTestId]);
  });

  it("search is case-insensitive and trims surrounding whitespace", () => {
    const tests = [
      displayTest({ testId: "t-1", testDisplayName: "LoginTest()" }),
    ];

    const results = filterTestResults(
      tests,
      filter({ search: "  LOGINtest  " }),
    );

    expect(results.map((r) => r.test.testId)).toEqual(["t-1"]);
  });

  it("a step-name-only match shows the parent test, expanded, with every step (not just the matched one)", () => {
    const tests = [
      displayTest({
        testId: "t-1",
        testDisplayName: "loginTest()",
        steps: [
          displayStep({ stepId: "s-1", stepName: "open homepage" }),
          displayStep({ stepId: "s-2", stepName: "submit credentials" }),
        ],
      }),
    ];

    const results = filterTestResults(tests, filter({ search: "credentials" }));

    expect(results).toHaveLength(1);
    expect(results[0]!.test.testId).toBe("t-1");
    expect(results[0]!.matchedStepIds).toEqual(new Set(["s-2"]));
    expect(results[0]!.forceExpandedForSearch).toBe(true);
  });

  it("never matches against failure detail/stack trace text", () => {
    const tests = [
      displayTest({
        testId: "t-1",
        testDisplayName: "loginTest()",
        status: "FAILED",
        detail: "AssertionError: boom",
      }),
    ];

    const results = filterTestResults(tests, filter({ search: "boom" }));

    expect(results).toHaveLength(0);
  });

  it("excludes a test that matches neither its own name/id nor any step name", () => {
    const tests = [
      displayTest({
        testId: "t-1",
        testDisplayName: "loginTest()",
        steps: [displayStep({ stepId: "s-1", stepName: "open homepage" })],
      }),
    ];

    const results = filterTestResults(tests, filter({ search: "checkout" }));

    expect(results).toHaveLength(0);
  });

  it.each([
    "RUNNING",
    "PASSED",
    "FAILED",
    "ABORTED",
    "SKIPPED",
    "INTERRUPTED",
  ] as const)(
    "the %s status filter shows only tests with that exact status",
    (status) => {
      const tests = [
        displayTest({ testId: "t-running", status: "RUNNING" }),
        displayTest({ testId: "t-passed", status: "PASSED" }),
        displayTest({ testId: "t-failed", status: "FAILED" }),
        displayTest({ testId: "t-aborted", status: "ABORTED" }),
        displayTest({ testId: "t-skipped", status: "SKIPPED" }),
        displayTest({ testId: "t-interrupted", status: "INTERRUPTED" }),
      ];

      const results = filterTestResults(tests, filter({ status }));

      expect(results).toHaveLength(1);
      expect(results[0]!.test.status).toBe(status);
    },
  );

  it("the Problems filter shows FAILED, ABORTED, and INTERRUPTED together, and nothing else", () => {
    const tests = [
      displayTest({ testId: "t-running", status: "RUNNING" }),
      displayTest({ testId: "t-passed", status: "PASSED" }),
      displayTest({ testId: "t-failed", status: "FAILED" }),
      displayTest({ testId: "t-aborted", status: "ABORTED" }),
      displayTest({ testId: "t-skipped", status: "SKIPPED" }),
      displayTest({ testId: "t-interrupted", status: "INTERRUPTED" }),
    ];

    const results = filterTestResults(tests, filter({ status: "PROBLEMS" }));

    expect(results.map((r) => r.test.testId).sort()).toEqual([
      "t-aborted",
      "t-failed",
      "t-interrupted",
    ]);
  });

  it("the Has artifacts / No artifacts evidence filters partition tests by hasArtifacts", () => {
    const tests = [
      displayTest({ testId: "t-with", hasArtifacts: true }),
      displayTest({ testId: "t-without", hasArtifacts: false }),
    ];

    expect(
      filterTestResults(tests, filter({ evidence: "HAS_ARTIFACTS" })).map(
        (r) => r.test.testId,
      ),
    ).toEqual(["t-with"]);
    expect(
      filterTestResults(tests, filter({ evidence: "NO_ARTIFACTS" })).map(
        (r) => r.test.testId,
      ),
    ).toEqual(["t-without"]);
  });

  it("combines search, status, and evidence filters together (AND, not OR)", () => {
    const tests = [
      // Matches search and status, but not evidence.
      displayTest({
        testId: "t-1",
        testDisplayName: "loginTest()",
        status: "FAILED",
        hasArtifacts: false,
      }),
      // Matches all three.
      displayTest({
        testId: "t-2",
        testDisplayName: "loginRetryTest()",
        status: "FAILED",
        hasArtifacts: true,
      }),
      // Matches search and evidence, but not status.
      displayTest({
        testId: "t-3",
        testDisplayName: "loginTest() variant",
        status: "PASSED",
        hasArtifacts: true,
      }),
    ];

    const results = filterTestResults(
      tests,
      filter({ search: "login", status: "FAILED", evidence: "HAS_ARTIFACTS" }),
    );

    expect(results.map((r) => r.test.testId)).toEqual(["t-2"]);
  });
});
