/// <reference types="vitest/config" />
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// Deliberately a plain process env var, not a VITE_-prefixed one: this only ever affects the dev/
// preview *server's* own proxy config, never anything shipped to the browser (a VITE_ prefix would
// bake it into the client bundle instead). Overridden by the dashboard E2E suite's isolated
// backend-unavailable test, which needs its own dashboard instance pointed at its own backend port
// rather than the shared 127.0.0.1:8080 instance every other test uses.
const runnerApiTarget =
  process.env.RUNNER_API_TARGET ?? "http://127.0.0.1:8080";

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // The frontend only ever calls relative URLs (/api/..., /actuator/...) - proxying here means
    // dev has no CORS configuration to maintain, and matches the same-origin production deployment
    // (Spring Boot serving both the API and these static files) this app is ultimately built for.
    // `vite preview` reuses this same config by default when `preview.proxy` isn't set separately.
    proxy: {
      "/api": { target: runnerApiTarget, changeOrigin: true },
      "/actuator": { target: runnerApiTarget, changeOrigin: true },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    globals: true,
    // Pinned so date-formatting assertions (Intl/toLocaleString - see formatLocalDateTime) are
    // deterministic across every machine/CI runner, regardless of its own default timezone. The
    // production build is unaffected - a real browser always uses the viewer's actual timezone.
    env: { TZ: "UTC" },
    // Mirrors the Java suite's own build/test-results/*.xml - a machine-readable report CI can
    // upload alongside coverage when the gate fails, not just console output.
    reporters: ["default", "junit"],
    outputFile: { junit: "test-results/junit.xml" },
    coverage: {
      provider: "v8",
      // Setting `include` (Vitest 4 dropped the old `all: true` flag) extends the report to
      // every matching source file, not just ones a test happens to import - a source file no
      // test ever touches shows up as 0% instead of being silently absent from the report,
      // which is what actually makes `thresholds` below a gate rather than a self-fulfilling
      // report over whatever was already covered.
      include: ["src/**/*.{ts,tsx}"],
      exclude: [
        "src/main.tsx",
        "src/**/*.test.{ts,tsx}",
        "src/test/**",
        // typed-openapi output (`npm run api:generate`) - hands off, exercised through contract
        // tests against the real backend, not line-by-line unit tests of generated plumbing.
        "src/api/generated/**",
      ],
      thresholds: {
        statements: 80,
        branches: 75,
        functions: 80,
        lines: 80,
      },
    },
  },
});
