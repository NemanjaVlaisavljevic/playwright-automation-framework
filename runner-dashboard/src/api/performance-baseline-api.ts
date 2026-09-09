import { PerformanceBaseline } from "../domain/performance-baseline";
import { RunnerApiError } from "./problem-detail";

const BASELINE_URL = "/performance/baseline.json";

/**
 * D4.4.3c - mirrors `getHealth()`'s own pattern in `runner-api.ts`: not part of the generated
 * client (`baseline.json` isn't an API response, it's a committed static asset served from
 * `public/`), so it's fetched and validated by hand, normalized into the same {@link RunnerApiError}
 * `network`/`http`/`contract` shape every other failure in this app already produces - a page
 * consuming this never needs a fourth, bespoke error kind to branch on.
 *
 * A real runtime `fetch()`, not a bundled `import` (unlike `performance-baseline.test.ts`'s own
 * contract test, which imports the file directly for its own, separate reason - see that file's own
 * comment). This does NOT avoid a rebuild/redeploy to publish a new baseline - `deploy/web/
 * Dockerfile` copies the whole built `dist/` (which `public/` is folded into by Vite) into the
 * `web` image, so a new `baseline.json` still ships via the normal image rebuild, same as any other
 * static asset (a review correction - an earlier version of this comment overclaimed otherwise).
 * The real benefit of a runtime fetch here is narrower: this file's contents stay out of the JS
 * bundle proper (so touching it alone doesn't force a full bundle rebuild/re-hash), and it goes
 * through the browser's own independent HTTP request/response path - real caching headers, a real
 * 404/500 if it's ever missing - rather than being silently baked into whatever `main.tsx` chunk
 * happened to import it.
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
