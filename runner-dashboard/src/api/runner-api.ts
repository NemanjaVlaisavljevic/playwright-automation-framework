import { z, ZodError } from "zod";
import { defaultFetcher } from "./generated/api.client";
import {
  createApiClient,
  ProblemDetail,
  TypedStatusError,
  type ArtifactSummaryResponse,
  type CapabilitiesResponse,
  type CreateRunRequest,
  type RunResponse,
  type TestCatalogEntry,
} from "./generated/runner-api";
import { RunnerApiError } from "./problem-detail";

/**
 * Re-exported here, not imported directly from `./generated/` elsewhere: the README's REST layer
 * invariant is "only `src/api/` imports from `src/api/generated/`," not "only `runner-api.ts`
 * imports the client" - `domain/`, `features/`, and everywhere else must go through this wrapper
 * for types too, the same way they already do for the functions below. Importing straight from
 * `./generated/runner-api` from outside this file was a real regression a previous review caught
 * (`RunsTable.tsx` and `domain/run.ts` both did it) - `scripts/check-import-boundaries.mjs` (wired
 * into `npm run check`) now fails the build if it happens again.
 */
export type {
  ArtifactSummaryResponse,
  CapabilitiesResponse,
  CreateRunRequest,
  RunResponse,
  TestCatalogEntry,
};

// The generated client's own request() does `new URL(baseUrl + path)`, and the WHATWG URL
// constructor rejects a relative string with no base ("" + "/api/v1/..." throws) - so this can't
// be "" the way a hand-written fetch wrapper could get away with. window.location.origin keeps it
// same-origin in effect (proxied to the backend by Vite in dev, see vite.config.ts; served
// same-origin in production, see the roadmap's packaging phase) without ever hardcoding a host.
const client = createApiClient(
  { fetch: defaultFetcher },
  window.location.origin,
);
client.setValidate("output");

/**
 * Turns a failure from the generated client into a {@link RunnerApiError}. Only this file and
 * `problem-detail.ts` (for the `ProblemDetail` schema itself) import from `./generated/` - nothing
 * outside this `api/` infrastructure layer ever touches generated code.
 *
 * Three distinct failure shapes reach here, and collapsing them into one `kind` would hide a real
 * distinction:
 * - {@link TypedStatusError} - the backend actually returned a 4xx/5xx. The generated client's own
 *   output validation deliberately skips every known error status (see `shouldValidateOutput` in
 *   `generated/runner-api.ts` - it only validates success responses and genuinely unexpected
 *   codes), so `ProblemDetail` is parsed here explicitly.
 * - {@link ZodError} - the backend returned a *success* status, but a body that doesn't match this
 *   app's own generated schema. This is a contract drift, not connectivity - the previous version
 *   of this function conflated the two, which meant a genuine backend contract break was reported
 *   to the user (and would be triaged) as "network unreachable."
 * - anything else - a real `fetch()` rejection (DNS, connection refused, CORS): the only other
 *   thing that can reach this catch, since every other failure this client can produce is one of
 *   the two cases above by construction.
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
 * Not part of the generated client - `/actuator/health` isn't in the app's own OpenAPI document -
 * so its response is validated by hand against a small local schema instead of a TS type
 * assertion, and normalized into the same `RunnerApiError` shape (network/http/contract) `unwrap`
 * produces for every other call, rather than a fourth, ad hoc failure mode.
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
 * The `CUSTOM`-suite picker's own allowlist - every `testKey` a caller may later put in
 * `CreateRunRequest.testKeys` and nothing else. `environment` is typed off `CreateRunRequest`
 * itself (not a new hand-written union) for the same contract-drift-proofing reason `domain/
 * run.ts`'s own `Environment` type is - this file cannot import that type back without a circular
 * dependency (`domain/run.ts` already imports from here).
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
 * Returns every artifact captured so far for the run - callers don't need a separate wrapper for
 * downloading one: each entry's own `downloadUrl` is a same-origin path meant to be used directly
 * as an `<a href>`/`<img src>` (mirrors how `RunResponse.processLogUrl` is already used in
 * `RunDetailsPage.tsx`), not fetched through this client - the download endpoint serves raw
 * image/zip/video bytes, not JSON, so there's nothing for `unwrap`'s Zod validation to check.
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
