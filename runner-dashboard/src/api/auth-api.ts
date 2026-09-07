import { z } from "zod";
import { getCsrfTokenFromCookie } from "./csrf";
import { RunnerApiError } from "./problem-detail";

/**
 * Hand-validated against a local schema, same rationale as `getHealth` in `runner-api.ts`:
 * `/api/v1/auth/me` and `/api/v1/auth/csrf` are deliberately kept out of the backend's OpenAPI
 * document (a simple, stable, rarely-changing shape not worth `npm run api:check:contract`
 * churn), so nothing here is routed through the generated client.
 *
 * `canManageRuns` is the one field every admin-gated control must check - never `authenticated`
 * alone. When GitHub OAuth2 isn't configured on the backend at all (the permissive chain -
 * default local `bootRun`, `dashboardE2eTest`), nobody is ever "authenticated" as anyone, but
 * every caller can still manage runs, exactly like this project's whole pre-D3.2 history.
 * `authenticationRequired` says whether a login concept even exists in this deployment - the
 * login control is hidden entirely when it's `false`, since a login link with no backing OAuth2
 * endpoint would be a dead link.
 */
const CurrentUserSchema = z.object({
  authenticationRequired: z.boolean(),
  canManageRuns: z.boolean(),
  authenticated: z.boolean(),
  login: z.string().optional(),
  avatarUrl: z.string().optional(),
});

export type CurrentUser = z.infer<typeof CurrentUserSchema>;

async function normalizedFetch(
  input: string,
  init?: RequestInit,
): Promise<Response> {
  let response: Response;
  try {
    response = await fetch(input, init);
  } catch (cause) {
    throw new RunnerApiError("network", 0, {
      message: "Could not reach the runner service.",
      cause,
    });
  }
  if (!response.ok) {
    throw new RunnerApiError("http", response.status);
  }
  return response;
}

export async function getCurrentUser(): Promise<CurrentUser> {
  const response = await normalizedFetch("/api/v1/auth/me");
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
  const parsed = CurrentUserSchema.safeParse(body);
  if (!parsed.success) {
    throw new RunnerApiError("contract", 0, {
      message:
        "The runner service returned a response that doesn't match its own contract.",
      cause: parsed.error,
    });
  }
  return parsed.data;
}

/**
 * Forces the backend to (re)issue the `XSRF-TOKEN` cookie for the current session - called once on
 * app bootstrap (see `AuthControls`) and again explicitly after logout, which does not reload the
 * page (a successful login does, via its own full-page redirect back to `/`, which re-triggers
 * this same bootstrap call for the new session automatically).
 */
export async function primeCsrfToken(): Promise<void> {
  await normalizedFetch("/api/v1/auth/csrf");
}

/**
 * Not a fetch call - OAuth2 login is inherently a full-page redirect round trip through
 * github.com, which `fetch`/`react-router` cannot drive. Consumed as a plain `<a href>`.
 */
export function loginHref(): string {
  return "/api/v1/auth/oauth2/authorization/github";
}

export async function logout(): Promise<void> {
  const token = getCsrfTokenFromCookie();
  await normalizedFetch("/api/v1/auth/logout", {
    method: "POST",
    ...(token !== undefined ? { headers: { "X-XSRF-TOKEN": token } } : {}),
  });
}
