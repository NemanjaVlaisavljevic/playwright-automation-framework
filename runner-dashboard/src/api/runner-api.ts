import { z, ZodError } from "zod";
import { defaultFetcher } from "./generated/api.client";
import {
  createApiClient,
  ProblemDetail,
  TypedStatusError,
  type ArtifactSummaryResponse,
  type CapabilitiesResponse,
  type CreateRunRequest,
  type Fetcher,
  type RunResponse,
  type TestCatalogEntry,
} from "./generated/runner-api";
import { getCsrfTokenFromCookie } from "./csrf";
import { RunnerApiError } from "./problem-detail";

/**
 * Re-exported here so `domain/`, `features/`, etc. never import `./generated/` directly.
 * `scripts/check-import-boundaries.mjs` enforces this.
 */
export type {
  ArtifactSummaryResponse,
  CapabilitiesResponse,
  CreateRunRequest,
  RunResponse,
  TestCatalogEntry,
};

/**
 * Attaches `X-XSRF-TOKEN` to every request when the cookie exists, covering all mutating calls
 * automatically. Requests stay same-origin (dev proxy / Caddy in prod), so no `credentials:
 * "include"` is needed.
 */
const csrfAwareFetcher: Fetcher["fetch"] = (input) => {
  const token = getCsrfTokenFromCookie();
  if (token === undefined) {
    return defaultFetcher(input);
  }
  const headers = new Headers(input.overrides?.headers);
  headers.set("X-XSRF-TOKEN", token);
  return defaultFetcher({
    ...input,
    overrides: { ...input.overrides, headers },
  });
};

// baseUrl can't be "": the generated client does `new URL(baseUrl + path)`, which throws on a
// relative string with no base. window.location.origin keeps requests same-origin without
// hardcoding a host.
const client = createApiClient(
  { fetch: csrfAwareFetcher },
  window.location.origin,
);
client.setValidate("output");

/**
 * Turns a failure from the generated client into a {@link RunnerApiError}, keeping
 * {@link TypedStatusError} (backend 4xx/5xx), {@link ZodError} (success status, schema mismatch),
 * and a raw `fetch()` rejection (network) as distinct `kind`s rather than collapsing them.
 */
async function unwrap<T>(request: Promise<T>): Promise<T> {
  try {
    return await request;
  } catch (cause) {
    if (cause instanceof TypedStatusError) {
      const parsed = ProblemDetail.safeParse(cause.response.data);
      throw new RunnerApiError("http", cause.status, {
        ...(parsed.success ? { problem: parsed.data } : {}),
        cause,
      });
    }
    if (cause instanceof ZodError) {
      throw new RunnerApiError("contract", 0, {
        message:
          "The runner service returned a response that doesn't match its own contract.",
        cause,
      });
    }
    throw new RunnerApiError("network", 0, {
      message: "Could not reach the runner service.",
      cause,
    });
  }
}

const HealthStatusSchema = z
  .object({ status: z.string() })
  .catchall(z.unknown());

export type HealthStatus = z.infer<typeof HealthStatusSchema>;

/**
 * `/actuator/health` isn't in the OpenAPI document, so it's validated by hand against a local
 * schema and normalized into the same `RunnerApiError` shape `unwrap` produces elsewhere.
 */
export async function getHealth(): Promise<HealthStatus> {
  let response: Response;
  try {
    response = await fetch("/actuator/health");
  } catch (cause) {
    throw new RunnerApiError("network", 0, {
      message: "Could not reach the runner service.",
      cause,
    });
  }
  if (!response.ok) {
    throw new RunnerApiError("http", response.status);
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch (cause) {
    throw new RunnerApiError("contract", 0, {
      message:
        "The runner service returned a response that doesn't match its own contract.",
      cause,
    });
  }

  const parsed = HealthStatusSchema.safeParse(body);
  if (!parsed.success) {
    throw new RunnerApiError("contract", 0, {
      message:
        "The runner service returned a response that doesn't match its own contract.",
      cause: parsed.error,
    });
  }
  return parsed.data;
}

export function getCapabilities(): Promise<CapabilitiesResponse> {
  return unwrap(client.get("/api/v1/capabilities"));
}

export function listRuns(): Promise<RunResponse[]> {
  return unwrap(client.get("/api/v1/runs"));
}

export function getRun(runId: string): Promise<RunResponse> {
  return unwrap(client.get("/api/v1/runs/{runId}", { path: { runId } }));
}

export function createRun(request: CreateRunRequest): Promise<RunResponse> {
  return unwrap(client.post("/api/v1/runs", { body: request }));
}

/**
 * Allowlist of `testKey` values for the `CUSTOM`-suite picker. `environment` is typed off
 * `CreateRunRequest` rather than `domain/run.ts`'s `Environment` to avoid a circular import.
 */
export function listPublicTests(
  environment: CreateRunRequest["environment"],
): Promise<TestCatalogEntry[]> {
  return unwrap(
    client
      .get("/api/v1/tests", { query: { environment } })
      .then((r) => r.tests),
  );
}

export function cancelRun(runId: string): Promise<RunResponse> {
  return unwrap(
    client.post("/api/v1/runs/{runId}/cancel", { path: { runId } }),
  );
}

/**
 * Each entry's `downloadUrl` is a same-origin path meant for direct use as `<a href>`/`<img src>`,
 * not fetched through this client - the download endpoint serves raw bytes, not JSON.
 */
export function listRunArtifacts(
  runId: string,
  testId?: string,
): Promise<ArtifactSummaryResponse[]> {
  return unwrap(
    client.get("/api/v1/runs/{runId}/artifacts", {
      path: { runId },
      ...(testId !== undefined ? { query: { testId } } : {}),
    }),
  );
}
