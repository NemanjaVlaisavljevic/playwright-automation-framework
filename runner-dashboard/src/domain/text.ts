/**
 * First line of a possibly multi-line string - used as a one-line failure summary next to the
 * full text. Formatting/redaction is done by the backend's `FailureDetailFormatter`.
 */
export function firstLine(text: string): string {
  const newlineIndex = text.indexOf("\n");
  return newlineIndex === -1 ? text : text.slice(0, newlineIndex);
}
