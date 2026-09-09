// D4.4.3a - shared archive loader for summarize-baseline.mjs and check-baseline.mjs: reads
// `<inputDir>/metadata.json` plus every `<inputDir>/raw/*.json` file, keyed by filename (without
// its extension). Deliberately lists the directory rather than trusting a fixed scenario list - a
// stale leftover file, or one from a different scenario set, must be visible to
// summarizeBaseline's own `assertExactScenarioSet` check as a real "unexpected extra" scenario,
// not silently ignored because nothing asked for it by name.
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";

async function readJson(filePath) {
  return JSON.parse(await readFile(filePath, "utf8"));
}

/**
 * @param {string} inputDir - absolute path to `performance/baselines/<run-id>/`.
 * @returns {Promise<{metadata: object, rawByScenario: Record<string, object>}>}
 */
export async function loadArchivedBaseline(inputDir) {
  const metadata = await readJson(path.join(inputDir, "metadata.json"));

  const rawDir = path.join(inputDir, "raw");
  const entries = await readdir(rawDir, { withFileTypes: true });
  const rawByScenario = {};
  for (const entry of entries) {
    if (!entry.isFile() || !entry.name.endsWith(".json")) {
      continue;
    }
    const scenarioId = entry.name.slice(0, -".json".length);
    rawByScenario[scenarioId] = await readJson(path.join(rawDir, entry.name));
  }

  return { metadata, rawByScenario };
}
