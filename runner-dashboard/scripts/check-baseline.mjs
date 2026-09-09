// D4.4.3a - drift check: proves the committed public/performance/baseline.json is exactly what
// summarize-baseline.mjs produces from its own committed source data (performance/baselines/<run-
// id>/), the same "regenerate and verify clean" pattern api:check:contract already uses for
// openapi/runner-api.json. Self-describing - reads which archived run backs the CURRENT baseline
// from the file's own `source.workflowRun.id`, so this never needs updating when a future baseline
// replaces this one. `generatedAt` is deliberately excluded from the identity-vs-content question:
// it is injected as the committed file's own value during regeneration (not `Date.now()`), so a
// real content drift is never masked by - nor falsely flagged because of - the timestamp that
// naturally differs on every real `summarize-baseline.mjs` run.
import { readFile } from "node:fs/promises";
import path from "node:path";
import {
  summarizeBaseline,
  SummarizeBaselineError,
} from "./lib/summarize-baseline-core.mjs";
import { SCENARIO_CONFIGS } from "./lib/scenario-configs.mjs";
import { loadArchivedBaseline } from "./lib/load-archived-baseline.mjs";

const REPO_ROOT = path.resolve(import.meta.dirname, "..", "..");
const BASELINE_PATH = path.resolve(
  import.meta.dirname,
  "..",
  "public/performance/baseline.json",
);

async function readJson(filePath) {
  return JSON.parse(await readFile(filePath, "utf8"));
}

async function main() {
  const committed = await readJson(BASELINE_PATH);
  const runId = committed?.source?.workflowRun?.id;
  if (!runId) {
    throw new Error(
      `${BASELINE_PATH}: source.workflowRun.id is missing - cannot determine which archived ` +
        "performance/baselines/<run-id>/ directory to regenerate from.",
    );
  }

  const inputDir = path.join(
    REPO_ROOT,
    "performance",
    "baselines",
    String(runId),
  );
  const { metadata, rawByScenario } = await loadArchivedBaseline(inputDir);

  const regenerated = summarizeBaseline({
    metadata,
    rawByScenario,
    scenarioConfigs: SCENARIO_CONFIGS,
    // Reuse the committed file's own generatedAt - see summarize-baseline-core.mjs's own header
    // comment for why this is the one field allowed to differ without indicating real drift.
    now: () => committed.generatedAt,
  });

  const regeneratedJson = JSON.stringify(regenerated, null, 2);
  const committedJson = JSON.stringify(committed, null, 2);
  if (regeneratedJson !== committedJson) {
    console.error(
      `${BASELINE_PATH} does not match what summarize-baseline.mjs regenerates from ` +
        `performance/baselines/${runId}/ - run ` +
        `"node scripts/summarize-baseline.mjs --input ../performance/baselines/${runId} ` +
        '--output public/performance/baseline.json" and commit the result.',
    );
    process.exitCode = 1;
    return;
  }
  console.log(
    `${BASELINE_PATH} matches performance/baselines/${runId}/ exactly.`,
  );
}

main().catch((error) => {
  if (error instanceof SummarizeBaselineError) {
    console.error(`check-baseline: ${error.message}`);
  } else {
    console.error(error);
  }
  process.exitCode = 1;
});
