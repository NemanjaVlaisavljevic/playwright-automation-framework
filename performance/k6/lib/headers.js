// D4.4.1b - HTTP header names are case-insensitive per spec, but a plain JS object key lookup on
// k6's `response.headers` is case-sensitive - Caddy/Tomcat's own actual casing must never be
// assumed. A shared, case-insensitive lookup used everywhere a header value is read (Retry-After,
// Content-Type, X-Request-ID), rather than each call site risking a silent false negative from a
// casing mismatch it never actually verified against the real running system.
export function getHeader(response, name) {
  const lower = name.toLowerCase();
  for (const key in response.headers) {
    if (key.toLowerCase() === lower) {
      return response.headers[key];
    }
  }
  return undefined;
}
