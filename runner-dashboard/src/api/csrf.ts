/**
 * Reads the `XSRF-TOKEN` cookie Spring Security's `CookieCsrfTokenRepository` writes (see
 * `GET /api/v1/auth/csrf`, primed on app bootstrap and again after logout) - the value is echoed
 * back via the `X-XSRF-TOKEN` header on every mutating request, Spring Security's own convention.
 */
export function getCsrfTokenFromCookie(): string | undefined {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
  const value = match?.[1];
  return value !== undefined ? decodeURIComponent(value) : undefined;
}
