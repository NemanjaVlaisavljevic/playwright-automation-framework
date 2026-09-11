/**
 * Reads the `XSRF-TOKEN` cookie Spring Security's `CookieCsrfTokenRepository` writes. The value is
 * echoed back via the `X-XSRF-TOKEN` header on every mutating request.
 */
export function getCsrfTokenFromCookie(): string | undefined {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
  const value = match?.[1];
  return value !== undefined ? decodeURIComponent(value) : undefined;
}
