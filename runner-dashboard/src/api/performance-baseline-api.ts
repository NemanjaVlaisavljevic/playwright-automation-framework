import { PerformanceBaseline } from "../domain/performance-baseline";
import { RunnerApiError } from "./problem-detail";

const BASELINE_URL = "/performance/baseline.json";

/**
 * `baseline.json` is a committed static asset under `public/`, fetched and validated by hand into
 * the same {@link RunnerApiError} shape as other failures. Uses runtime `fetch()`, not a bundled
 * `import`, to keep it out of the JS bundle and get real HTTP caching/404 behavior.
 */
export async function getPerformanceBaseline(): Promise<PerformanceBaseline> {
  let response: Response;
  try {
    response = await fetch(BASELINE_URL);
  } catch (cause) {
    throw new RunnerApiError("network", 0, {
      message: "Could not load the performance baseline.",
      cause,
    });
  }
  if (!response.ok) {
    throw new RunnerApiError("http", response.status, {
      message: `The performance baseline could not be loaded (HTTP ${response.status}).`,
    });
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch (cause) {
    throw new RunnerApiError("contract", 0, {
      message: "The performance baseline response was not valid JSON.",
      cause,
    });
  }

  const parsed = PerformanceBaseline.safeParse(body);
  if (!parsed.success) {
    throw new RunnerApiError("contract", 0, {
      message: "The performance baseline does not match its own contract.",
      cause: parsed.error,
    });
  }
  return parsed.data;
}
