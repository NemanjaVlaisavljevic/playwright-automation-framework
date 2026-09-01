import type { z } from "zod";
import { ProblemDetail } from "./generated/runner-api";

/**
 * Deliberately `z.infer<typeof ProblemDetail>`, not the generated `.d.ts`'s own `ProblemDetail`
 * type: that hand-written sidecar declares optional fields as `type?: string` (TS's "key may be
 * absent" convention), while a real `ProblemDetail.safeParse(...).data` value - produced by the
 * same Zod schema this type is inferred from - types an absent optional field as `type?: string |
 * undefined`. Under this project's `exactOptionalPropertyTypes`, those are different types; using
 * the schema's own inferred type instead of the sidecar's keeps `unwrap` in `runner-api.ts` (which
 * assigns a real parsed value here) type-checking against the shape it actually produces.
 */
type ProblemDetailValue = z.infer<typeof ProblemDetail>;

/**
 * - `"http"`: the backend actually responded with a 4xx/5xx status. `problem` is populated only
 *   when that response body itself parsed as a valid `ProblemDetail` - a 502 from an intermediary
 *   (e.g. Vite's dev proxy when the backend is down) is still `"http"`, just with no `problem`.
 * - `"network"`: no HTTP response was ever received (`fetch` itself rejected - DNS, connection
 *   refused, CORS, etc.).
 * - `"contract"`: a response was received and parsed as JSON, but its shape didn't match this
 *   app's own expectations (a generated schema's Zod validation failed, or a hand-validated
 *   response like `/actuator/health` failed its own schema). This is deliberately never folded
 *   into `"network"` - a contract violation means the backend is reachable and responding, just
 *   not in the shape the frontend was built against, which is a different problem to react to
 *   (and to alert on) than the backend being down.
 */
export type RunnerApiErrorKind = "http" | "network" | "contract";

interface RunnerApiErrorOptions {
  problem?: ProblemDetailValue;
  message?: string;
  cause?: unknown;
}

/**
 * Normalized error shape for every failure the API layer can produce. Components consume this
 * instead of `Response`/`TypedStatusError`/`ZodError` directly, and never parse a `ProblemDetail`
 * body themselves - see `runner-api.ts`'s `unwrap`, which is the only place a `ProblemDetail` is
 * parsed. The original failure is preserved via the standard `Error.cause` (not a bespoke field),
 * so nothing is lost for diagnosis even though components only ever branch on `kind`.
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
    // Guarded (not a direct `this.problem = options.problem`) because `exactOptionalPropertyTypes`
    // treats an optional field as "present with a ProblemDetail, or absent" - never "present with
    // undefined" - so assigning a possibly-undefined value directly would be a type error.
    if (options.problem !== undefined) {
      this.problem = options.problem;
    }
  }
}
