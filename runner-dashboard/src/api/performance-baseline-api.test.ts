import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { server } from "../test/msw/server";
import { getPerformanceBaseline } from "./performance-baseline-api";
import { RunnerApiError } from "./problem-detail";

async function catchError(promise: Promise<unknown>): Promise<RunnerApiError> {
  try {
    await promise;
  } catch (error) {
    expect(error).toBeInstanceOf(RunnerApiError);
    return error as RunnerApiError;
  }
  throw new Error("expected the promise to reject");
}

const VALID_BASELINE = {
  schemaVersion: 1,
  generatedAt: "2026-06-01T12:00:00.000Z",
  source: {
    measuredAt: "2026-01-01T00:00:00Z",
    commitSha: "000000000000000000000000000000000000dead",
    profile: "production-policy",
    executionMode: "cold",
    repetitions: 1,
    runnerImage: "ubuntu-latest",
    k6Version: "1.5.0",
    workflowRun: {
      id: 1,
      attempt: 1,
      url: "https://github.com/example/example/actions/runs/1",
    },
  },
  scenarios: [
    {
      scenarioId: "health",
      displayName: "Health checks",
      status: "PASSED",
      latencies: [
        {
          metricId: "liveness",
          displayName: "Liveness",
          sampleCount: 30,
          p50Ms: 3.5,
          p95Ms: 5.0,
          p99Ms: 6.0,
          p95LimitMs: 50,
          passed: true,
        },
      ],
      signals: [],
    },
  ],
};

describe("getPerformanceBaseline", () => {
  it("returns the parsed baseline on success", async () => {
    server.use(
      http.get("/performance/baseline.json", () =>
        HttpResponse.json(VALID_BASELINE),
      ),
    );

    await expect(getPerformanceBaseline()).resolves.toEqual(VALID_BASELINE);
  });

  it("normalizes a non-2xx response into an http RunnerApiError", async () => {
    server.use(
      http.get(
        "/performance/baseline.json",
        () => new HttpResponse(null, { status: 404 }),
      ),
    );

    const error = await catchError(getPerformanceBaseline());
    expect(error.kind).toBe("http");
    expect(error.status).toBe(404);
  });

  it("normalizes a network failure into a network RunnerApiError", async () => {
    server.use(
      http.get("/performance/baseline.json", () => HttpResponse.error()),
    );

    const error = await catchError(getPerformanceBaseline());
    expect(error.kind).toBe("network");
  });

  it("normalizes a response that doesn't match the contract into a contract RunnerApiError", async () => {
    server.use(
      http.get("/performance/baseline.json", () =>
        HttpResponse.json({ unexpected: "shape" }),
      ),
    );

    const error = await catchError(getPerformanceBaseline());
    expect(error.kind).toBe("contract");
  });

  it("normalizes a non-JSON response body into a contract RunnerApiError", async () => {
    server.use(
      http.get(
        "/performance/baseline.json",
        () => new HttpResponse("not json", { status: 200 }),
      ),
    );

    const error = await catchError(getPerformanceBaseline());
    expect(error.kind).toBe("contract");
  });
});
