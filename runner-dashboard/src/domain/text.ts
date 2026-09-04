/**
 * The first line of a possibly multi-line string - used as a one-line failure summary next to the
 * full (redacted) text, which stays verbatim in its own `<pre>`. Deliberately this simple: parsing
 * an exception string into a structured object (message/type/stack frames) would be fragile and
 * add complexity the UI doesn't actually need - the backend's own `FailureDetailFormatter` already
 * does the real formatting/redaction work.
 */
export function firstLine(text: string): string {
  const newlineIndex = text.indexOf("\n");
  return newlineIndex === -1 ? text : text.slice(0, newlineIndex);
}
