import type { z } from "zod";
import { ProblemDetail } from "./generated/runner-api";

/**
 * Uses `z.infer<typeof ProblemDetail>`, not the generated `.d.ts` type: under this project's
 * `exactOptionalPropertyTypes`, the sidecar's `type?: string` and the schema's actual
 * `type?: string | undefined` are different types.
 */
type ProblemDetailValue = z.infer<typeof ProblemDetail>;

/**
 * `"http"`: backend responded 4xx/5xx. `"network"`: no response was received at all. `"contract"`:
 * response received but shape didn't match expectations - kept distinct from `"network"`.
 */
export type RunnerApiErrorKind = "http" | "network" | "contract";

interface RunnerApiErrorOptions {
  problem?: ProblemDetailValue;
  message?: string;
  cause?: unknown;
}

/**
 * Normalized error shape for every failure the API layer can produce. Components branch on `kind`
 * only; the original failure is preserved via the standard `Error.cause`.
 */
export class RunnerApiError extends Error {
  readonly kind: RunnerApiErrorKind;
  readonly status: number;
  readonly problem?: ProblemDetailValue;

  constructor(
    kind: RunnerApiErrorKind,
    status: number,
    options: RunnerApiErrorOptions = {},
  ) {
    super(
      options.message ??
        options.problem?.detail ??
        options.problem?.title ??
        `Request failed with status ${status}`,
      options.cause !== undefined ? { cause: options.cause } : undefined,
    );
    this.name = "RunnerApiError";
    this.kind = kind;
    this.status = status;
    // Guarded, not a direct assignment: exactOptionalPropertyTypes rejects assigning undefined
    // to an optional field.
    if (options.problem !== undefined) {
      this.problem = options.problem;
    }
  }
}

/**
 * Shared 401/403 message, composed in front of each admin-gated call site's own local error
 * describer (e.g. `describePermissionError(error) ?? describeLaunchError(error)`).
 */
export function describePermissionError(error: unknown): string | undefined {
  if (!(error instanceof RunnerApiError) || error.kind !== "http") {
    return undefined;
  }
  if (error.status === 401) {
    return "Please log in with GitHub to do this.";
  }
  if (error.status === 403) {
    return "You're logged in, but this account isn't allowed to do that.";
  }
  return undefined;
}
