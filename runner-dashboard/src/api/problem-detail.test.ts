import { describe, expect, it } from "vitest";
import { RunnerApiError, describePermissionError } from "./problem-detail";

describe("RunnerApiError", () => {
  it("uses the problem detail's own detail message when present", () => {
    const error = new RunnerApiError("http", 404, {
      problem: {
        title: "Not Found",
        status: 404,
        detail: "No run found for runId: abc",
        instance: "/api/v1/runs/abc",
      },
    });

    expect(error.kind).toBe("http");
    expect(error.status).toBe(404);
    expect(error.message).toBe("No run found for runId: abc");
    expect(error.problem?.title).toBe("Not Found");
  });

  it("falls back to an explicit message when there is no problem detail", () => {
    const networkCause = new TypeError("fetch failed");
    const error = new RunnerApiError("network", 0, {
      message: "Could not reach the runner service.",
      cause: networkCause,
    });

    expect(error.kind).toBe("network");
    expect(error.message).toBe("Could not reach the runner service.");
    expect(error.problem).toBeUndefined();
    expect(error.cause).toBe(networkCause);
  });

  it("falls back to a status-based message when nothing else is given", () => {
    const error = new RunnerApiError("http", 503);

    expect(error.message).toBe("Request failed with status 503");
    expect(error.problem).toBeUndefined();
  });
});

describe("describePermissionError", () => {
  it("returns a login message for a 401", () => {
    expect(describePermissionError(new RunnerApiError("http", 401))).toBe(
      "Please log in with GitHub to do this.",
    );
  });

  it("returns a permission message for a 403", () => {
    expect(describePermissionError(new RunnerApiError("http", 403))).toBe(
      "You're logged in, but this account isn't allowed to do that.",
    );
  });

  it("returns undefined for any other status", () => {
    expect(
      describePermissionError(new RunnerApiError("http", 503)),
    ).toBeUndefined();
  });

  it("returns undefined for a non-http error kind", () => {
    expect(
      describePermissionError(new RunnerApiError("network", 0)),
    ).toBeUndefined();
  });

  it("returns undefined for a non-RunnerApiError value", () => {
    expect(describePermissionError(new Error("boom"))).toBeUndefined();
  });
});
