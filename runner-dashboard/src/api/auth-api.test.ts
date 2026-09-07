import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { server } from "../test/msw/server";
import { getCurrentUser, logout, primeCsrfToken } from "./auth-api";
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

describe("getCurrentUser", () => {
  it("returns the parsed permissive-chain shape (GitHub OAuth2 not configured)", async () => {
    server.use(
      http.get("/api/v1/auth/me", () =>
        HttpResponse.json({
          authenticationRequired: false,
          canManageRuns: true,
          authenticated: false,
        }),
      ),
    );

    await expect(getCurrentUser()).resolves.toEqual({
      authenticationRequired: false,
      canManageRuns: true,
      authenticated: false,
    });
  });

  it("returns the parsed anonymous shape (GitHub OAuth2 enabled, not logged in)", async () => {
    server.use(
      http.get("/api/v1/auth/me", () =>
        HttpResponse.json({
          authenticationRequired: true,
          canManageRuns: false,
          authenticated: false,
        }),
      ),
    );

    await expect(getCurrentUser()).resolves.toEqual({
      authenticationRequired: true,
      canManageRuns: false,
      authenticated: false,
    });
  });

  it("returns the parsed authenticated-admin shape", async () => {
    server.use(
      http.get("/api/v1/auth/me", () =>
        HttpResponse.json({
          authenticationRequired: true,
          canManageRuns: true,
          authenticated: true,
          login: "octocat",
          avatarUrl: "https://example.invalid/avatar.png",
        }),
      ),
    );

    await expect(getCurrentUser()).resolves.toEqual({
      authenticationRequired: true,
      canManageRuns: true,
      authenticated: true,
      login: "octocat",
      avatarUrl: "https://example.invalid/avatar.png",
    });
  });

  it("normalizes a non-2xx response into an http RunnerApiError", async () => {
    server.use(
      http.get(
        "/api/v1/auth/me",
        () => new HttpResponse(null, { status: 503 }),
      ),
    );

    const error = await catchError(getCurrentUser());
    expect(error.kind).toBe("http");
    expect(error.status).toBe(503);
  });

  it("normalizes a network failure into a network RunnerApiError", async () => {
    server.use(http.get("/api/v1/auth/me", () => HttpResponse.error()));

    const error = await catchError(getCurrentUser());
    expect(error.kind).toBe("network");
  });

  it("normalizes a response that doesn't match the schema into a contract RunnerApiError", async () => {
    server.use(
      http.get("/api/v1/auth/me", () =>
        HttpResponse.json({ unexpected: "shape" }),
      ),
    );

    const error = await catchError(getCurrentUser());
    expect(error.kind).toBe("contract");
  });
});

describe("primeCsrfToken", () => {
  it("resolves once the backend confirms priming", async () => {
    await expect(primeCsrfToken()).resolves.toBeUndefined();
  });

  it("throws a network RunnerApiError when unreachable", async () => {
    server.use(http.get("/api/v1/auth/csrf", () => HttpResponse.error()));

    await catchError(primeCsrfToken());
  });
});

describe("logout", () => {
  it("resolves once the backend confirms logout", async () => {
    server.use(
      http.post(
        "/api/v1/auth/logout",
        () => new HttpResponse(null, { status: 204 }),
      ),
    );

    await expect(logout()).resolves.toBeUndefined();
  });

  it("normalizes a non-2xx response into an http RunnerApiError", async () => {
    server.use(
      http.post(
        "/api/v1/auth/logout",
        () => new HttpResponse(null, { status: 403 }),
      ),
    );

    const error = await catchError(logout());
    expect(error.kind).toBe("http");
    expect(error.status).toBe(403);
  });
});
