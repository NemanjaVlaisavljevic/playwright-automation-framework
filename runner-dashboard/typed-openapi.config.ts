import { defineConfig } from "typed-openapi";

export default defineConfig({
  input: "./openapi/runner-api.json",
  output: "./src/api/generated/runner-api.ts",
  runtime: "zod",
  validation: "strict",
  validateSide: "output",
  defaultFetcher: true,
});
