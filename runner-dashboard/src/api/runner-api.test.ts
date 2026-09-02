import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { server } from "../test/msw/server";
import { RunnerApiError } from "./problem-detail";
import {
  cancelRun,
  createRun,
  getCapabilities,
  getHealth,
  getRun,
  listRunArtifacts,
  listRuns,
} from "./runner-api";

const runResponse = (overrides: Partial<Record<string, unknown>> = {}) => ({
  runId: "run-1",
  environment: "PUBLIC",
  suite: "SMOKE",
  status: "QUEUED",
  requestedAt: "2026-09-01T10:00:00Z",
  processLogUrl: "/api/v1/runs/run-1/log",
  ...overrides,
});

async function catchError(promise: Promise<unknown>): Promise<RunnerApiError> {
  try {
    await promise;
  } catch (error) {
    expect(error).toBeInstanceOf(RunnerApiError);
    return error as RunnerApiError;
  }
  throw new Error("expected the promise to reject");
}

describe("getHealth", () => {
  it("returns the parsed health status", async () => {
    await expect(getHealth()).resolves.toEqual({ status: "UP" });
  });

  it("normalizes a non-2xx response into an http RunnerApiError", async () => {
    server.use(
      http.get(
        "/actuator/health",
        () => new HttpResponse(null, { status: 503 }),
      ),
    );

    const error = await catchError(getHealth());
    expect(error.kind).toBe("http");
    expect(error.status).toBe(503);
  });

  it("normalizes a network failure into a network RunnerApiError", async () => {
    server.use(http.get("/actuator/health", () => HttpResponse.error()));

    const error = await catchError(getHealth());
    expect(error.kind).toBe("network");
    expect(error.status).toBe(0);
  });

  it("normalizes an empty 200 payload into a contract RunnerApiError, not 'undefined'", async () => {
    server.use(http.get("/actuator/health", () => HttpResponse.json({})));

    const error = await catchError(getHealth());
    expect(error.kind).toBe("contract");
  });

  it("normalizes a malformed (non-JSON) 200 body into a contract RunnerApiError", async () => {
    server.use(
      http.get(
        "/actuator/health",
        () =>
          new HttpResponse("not json", {
            status: 200,
            headers: { "Content-Type": "application/json" },
          }),
      ),
    );

    const error = await catchError(getHealth());
    expect(error.kind).toBe("contract");
  });
});

describe("getCapabilities", () => {
  it("returns the parsed capabilities response", async () => {
    const capabilities = await getCapabilities();

    expect(capabilities.environments).toHaveLength(1);
    expect(capabilities.environments[0]?.name).toBe("PUBLIC");
  });

  it("normalizes an invalid 200 payload into a contract RunnerApiError, not a network failure", async () => {
    server.use(
      http.get("/api/v1/capabilities", () =>
        HttpResponse.json({ apiVersion: "v1" }),
      ),
    );

    const error = await catchError(getCapabilities());
    expect(error.kind).toBe("contract");
    expect(error.status).toBe(0);
  });
});

describe("getRun", () => {
  it("normalizes a 404 ProblemDetail response into an http RunnerApiError with the parsed detail", async () => {
    server.use(
      http.get("/api/v1/runs/:runId", () =>
        HttpResponse.json(
          {
            title: "Not Found",
            status: 404,
            detail: "No run found for runId: missing",
            instance: "/api/v1/runs/missing",
          },
          { status: 404 },
        ),
      ),
    );

    const error = await catchError(getRun("missing"));
    expect(error.kind).toBe("http");
    expect(error.status).toBe(404);
    expect(error.message).toBe("No run found for runId: missing");
    expect(error.problem?.title).toBe("Not Found");
  });

  it("normalizes an http error with a non-ProblemDetail body (e.g. a proxy's own 502) without a problem", async () => {
    server.use(
      http.get(
        "/api/v1/runs/:runId",
        () => new HttpResponse("Bad Gateway", { status: 502 }),
      ),
    );

    const error = await catchError(getRun("anything"));
    expect(error.kind).toBe("http");
    expect(error.status).toBe(502);
    expect(error.problem).toBeUndefined();
  });
});

describe("listRunArtifacts", () => {
  const artifact = (overrides: Partial<Record<string, unknown>> = {}) => ({
    artifactId: "a1",
    testId: "test-1",
    testDisplayName: "loginTest()",
    type: "SCREENSHOT",
    mediaType: "image/png",
    sizeBytes: 1024,
    createdAt: "2026-09-01T10:00:05Z",
    downloadUrl: "/api/v1/runs/run-1/artifacts/a1",
    ...overrides,
  });

  it("returns the parsed artifact list", async () => {
    server.use(
      http.get("/api/v1/runs/:runId/artifacts", () =>
        HttpResponse.json([artifact()]),
      ),
    );

    await expect(listRunArtifacts("run-1")).resolves.toEqual([artifact()]);
  });

  it("forwards the testId as a query parameter, fully decoded back to the original JUnit unique ID", async () => {
    // A real JUnit `TestIdentifier.getUniqueId()` - brackets, colons, parentheses, a dot-qualified
    // class name - is exactly the kind of value naive string concatenation into a query string
    // would mangle. Asserting via `url.searchParams.get` (not a raw substring match on the URL) is
    // the point: it proves the server receives this value back verbatim after a real
    // encode-then-decode round trip, not merely that *some* escaped form appears in the URL.
    const testId =
      "[engine:junit-jupiter]/[class:HomePageTest]/[method:showsRooms()]";
    server.use(
      http.get("/api/v1/runs/:runId/artifacts", ({ request }) => {
        const url = new URL(request.url);
        return HttpResponse.json(
          url.searchParams.get("testId") === testId ? [artifact()] : [],
        );
      }),
    );

    await expect(listRunArtifacts("run-1", testId)).resolves.toHaveLength(1);
  });

  it("normalizes a 404 into an http RunnerApiError", async () => {
    server.use(
      http.get(
        "/api/v1/runs/:runId/artifacts",
        () => new HttpResponse(null, { status: 404 }),
      ),
    );

    const error = await catchError(listRunArtifacts("missing"));
    expect(error.kind).toBe("http");
    expect(error.status).toBe(404);
  });
});

describe("listRuns, createRun, cancelRun", () => {
  it("call through to the expected endpoints and return the parsed response", async () => {
    server.use(
      http.get("/api/v1/runs", () => HttpResponse.json([])),
      http.post("/api/v1/runs", () =>
        HttpResponse.json(runResponse(), { status: 202 }),
      ),
      http.post("/api/v1/runs/:runId/cancel", () =>
        HttpResponse.json(runResponse({ status: "CANCELLED" })),
      ),
    );

    await expect(listRuns()).resolves.toEqual([]);
    await expect(
      createRun({ environment: "PUBLIC", suite: "SMOKE" }),
    ).resolves.toMatchObject({
      runId: "run-1",
    });
    await expect(cancelRun("run-1")).resolves.toMatchObject({
      status: "CANCELLED",
    });
  });
});
