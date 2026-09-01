// Fails if any of the given paths have uncommitted changes, INCLUDING new/untracked files -
// `git diff --exit-code` alone misses untracked files entirely, which is exactly the gap that let
// `api:check` pass while the whole generated-client directory was still untracked. Used to verify
// a regenerate step (api:generate, or export+generate together) produced no drift.
import { execFileSync } from "node:child_process";

const paths = process.argv.slice(2);
if (paths.length === 0) {
  console.error("Usage: check-git-clean.mjs <path...>");
  process.exit(1);
}

const output = execFileSync("git", ["status", "--porcelain", "--", ...paths], {
  encoding: "utf8",
});

if (output.trim().length > 0) {
  console.error(`Uncommitted changes found under: ${paths.join(", ")}`);
  console.error(output);
  process.exit(1);
}

console.log(`Clean: ${paths.join(", ")}`);
