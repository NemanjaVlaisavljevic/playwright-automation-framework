// D4.4.3a - CLI wrapper: reads a permanently-archived raw-CI-results directory
// (performance/baselines/<run-id>/ - metadata.json + raw/<scenario>.json, see that directory's own
// README) and writes runner-dashboard/public/performance/baseline.json. All summarization/
// validation logic lives in scripts/lib/summarize-baseline-core.mjs (filesystem-free, unit-tested
// via its own golden-output test) - this file owns only I/O and the real, project-specific mapping
// from this repo's six k6 scenarios to the baseline's scenario/metric shape (scripts/lib/
// scenario-configs.mjs, shared with check-baseline.mjs's own drift check).
//
// Usage: node scripts/summarize-baseline.mjs --input ../performance/baselines/<run-id> \
//   --output public/performance/baseline.json
import { writeFile } from "node:fs/promises";
import path from "node:path";
import {
  summarizeBaseline,
  SummarizeBaselineError,
} from "./lib/summarize-baseline-core.mjs";
import { SCENARIO_CONFIGS } from "./lib/scenario-configs.mjs";
import { loadArchivedBaseline } from "./lib/load-archived-baseline.mjs";

function parseArgs(argv) {
  const args = { input: null, output: null };
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === "--input") {
      args.input = argv[++i];
    } else if (argv[i] === "--output") {
      args.output = argv[++i];
    }
  }
  if (!args.input || !args.output) {
    throw new Error(
      "Usage: summarize-baseline.mjs --input <dir> --output <path>",
    );
  }
  return args;
}

async function main() {
  const { input, output } = parseArgs(process.argv.slice(2));
  const inputDir = path.resolve(process.cwd(), input);
  const outputPath = path.resolve(process.cwd(), output);

  const { metadata, rawByScenario } = await loadArchivedBaseline(inputDir);

  const baseline = summarizeBaseline({
    metadata,
    rawByScenario,
    scenarioConfigs: SCENARIO_CONFIGS,
  });

  await writeFile(outputPath, `${JSON.stringify(baseline, null, 2)}\n`, "utf8");
  console.log(`Wrote ${outputPath}`);
}

main().catch((error) => {
  if (error instanceof SummarizeBaselineError) {
    console.error(`summarize-baseline: ${error.message}`);
  } else {
    console.error(error);
  }
  process.exitCode = 1;
});
