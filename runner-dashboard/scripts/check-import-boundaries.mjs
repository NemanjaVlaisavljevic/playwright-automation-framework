// Enforces the "only src/api/ imports from src/api/generated/" invariant documented in the
// README's "REST layer" section. oxlint's import plugin has no equivalent of eslint-plugin-import's
// `no-restricted-paths` (checked before writing this - see the rule list in
// node_modules/oxlint/configuration_schema.json), so this is a small standalone check instead,
// following the same pattern as check-git-clean.mjs.
//
// This exists because the boundary was violated twice in real code (RunsTable.tsx, domain/run.ts
// both imported straight from ./generated/ before a review caught it) - a documented convention
// with nothing enforcing it just drifts.
import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";

const projectRoot = path.resolve(import.meta.dirname, "..");
const srcDir = path.join(projectRoot, "src");
const allowedDir = path.join(srcDir, "api") + path.sep;
const importPattern = /from\s+["']([^"']*\bapi\/generated\b[^"']*)["']/g;

function collectSourceFiles(dir, files = []) {
  for (const entry of readdirSync(dir)) {
    const fullPath = path.join(dir, entry);
    if (statSync(fullPath).isDirectory()) {
      collectSourceFiles(fullPath, files);
    } else if (/\.(ts|tsx)$/.test(entry)) {
      files.push(fullPath);
    }
  }
  return files;
}

const violations = [];
for (const file of collectSourceFiles(srcDir)) {
  if (file.startsWith(allowedDir)) {
    continue; // src/api/ is the one place allowed to import generated code directly.
  }
  const content = readFileSync(file, "utf8");
  for (const match of content.matchAll(importPattern)) {
    violations.push(
      `${path.relative(projectRoot, file)}: imports "${match[1]}"`,
    );
  }
}

if (violations.length > 0) {
  console.error(
    "Only src/api/ may import from src/api/generated/ - go through src/api/runner-api.ts " +
      "(or src/api/problem-detail.ts for the ProblemDetail schema) instead:",
  );
  for (const violation of violations) {
    console.error(`  ${violation}`);
  }
  process.exit(1);
}

console.log("OK: no code outside src/api/ imports from src/api/generated/.");
