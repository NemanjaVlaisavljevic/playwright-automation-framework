import { z } from "zod";
import { getCsrfTokenFromCookie } from "./csrf";
import { RunnerApiError } from "./problem-detail";

/**
 * Hand-validated, not routed through the generated client (`/auth/me`, `/auth/csrf` are outside
 * the OpenAPI document). `canManageRuns`, not `authenticated`, is what admin-gated controls must
 * check: with OAuth2 unconfigured, nobody is "authenticated" but every caller can still manage runs.
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
 * Forces the backend to (re)issue the `XSRF-TOKEN` cookie. Called on app bootstrap and again after
 * logout, since logout doesn't reload the page (login does, via its own redirect).
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
