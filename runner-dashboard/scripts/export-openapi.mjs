// Fetches runner-service's live /v3/api-docs and writes it, pretty-printed, to
// openapi/runner-api.json. Requires runner-service already running (see README's "Typed REST
// client" section) - this script only exports, it does not start/stop the backend itself, so the
// same script works identically whether a developer started it by hand or a CI job started it as
// a prior step.
import { writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const baseUrl = process.env.RUNNER_SERVICE_URL ?? "http://127.0.0.1:8080";
const timeoutMs = Number(process.env.RUNNER_SERVICE_TIMEOUT_MS ?? 10_000);
const outputPath = path.resolve(
  fileURLToPath(new URL("..", import.meta.url)),
  "openapi/runner-api.json",
);

let response;
try {
  response = await fetch(`${baseUrl}/v3/api-docs`, {
    signal: AbortSignal.timeout(timeoutMs),
  });
} catch (cause) {
  console.error(
    `Could not reach ${baseUrl}/v3/api-docs within ${timeoutMs}ms - is runner-service running? ` +
      `(./gradlew.bat :runner-service:bootRun from the repository root)`,
  );
  console.error(cause);
  process.exit(1);
}

if (!response.ok) {
  console.error(`GET ${baseUrl}/v3/api-docs -> HTTP ${response.status}`);
  process.exit(1);
}

const spec = await response.json();
await writeFile(outputPath, `${JSON.stringify(spec, null, 2)}\n`, "utf8");
console.log(`Wrote ${outputPath}`);
