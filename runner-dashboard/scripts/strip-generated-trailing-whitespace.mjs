// `typed-openapi` emits trailing whitespace on some generated lines (confirmed in
// `runner-api.ts`/`runner-api.types.d.ts`) - harmless to TypeScript, but `git diff --check` (unlike
// `npm run check`, which deliberately excludes `src/api/generated/` from Prettier/lint - see the
// README's "Typed REST client" section) does flag it, so a real CI diff-check gate would fail on a
// file nobody hand-edited. Run as a deterministic post-processing step right after `api:generate`,
// never as a substitute for it - see `package.json`'s `api:generate` script.
import { readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";

const targets = process.argv.slice(2);
if (targets.length === 0) {
  console.error(
    "Usage: strip-generated-trailing-whitespace.mjs <file-or-dir...>",
  );
  process.exit(1);
}

function collectFiles(target, files = []) {
  if (statSync(target).isDirectory()) {
    for (const entry of readdirSync(target)) {
      collectFiles(path.join(target, entry), files);
    }
  } else {
    files.push(target);
  }
  return files;
}

let changedCount = 0;
for (const target of targets) {
  for (const file of collectFiles(target)) {
    const original = readFileSync(file, "utf8");
    // Strips trailing spaces/tabs from each line only - never touches the line-ending characters
    // themselves (`\n` vs `\r\n`), so this can't change a file's own line-ending convention. The
    // `|$` alternative matters: `typed-openapi`'s own output ends with trailing spaces on its very
    // last line with no final newline at all, which `(\r?\n)` alone would never match.
    const stripped = original.replace(/[ \t]+(\r?\n|$)/g, "$1");
    if (stripped !== original) {
      writeFileSync(file, stripped, "utf8");
      changedCount += 1;
      console.log(`Stripped trailing whitespace: ${file}`);
    }
  }
}

console.log(
  changedCount === 0
    ? "No trailing whitespace found."
    : `Stripped trailing whitespace from ${changedCount} file(s).`,
);
