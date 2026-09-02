import { http, HttpResponse } from "msw";

/**
 * Default happy-path handlers used by every test unless overridden with `server.use(...)` for a
 * specific case (error paths, etc.) - keeps most tests from having to mock every endpoint they
 * incidentally touch (e.g. any test that renders `RunListPage`).
 */
export const handlers = [
  http.get("/actuator/health", () => HttpResponse.json({ status: "UP" })),
  http.get("/api/v1/capabilities", () =>
    HttpResponse.json({
      apiVersion: "v1",
      eventSchemaVersion: "1.0",
      environments: [
        {
          name: "PUBLIC",
          suites: ["SMOKE", "API", "UI", "JOURNEY", "REGRESSION"],
        },
      ],
    }),
  ),
  http.get("/api/v1/runs", () => HttpResponse.json([])),
  http.get("/api/v1/runs/:runId", ({ params }) =>
    HttpResponse.json({
      runId: params.runId,
      environment: "PUBLIC",
      suite: "SMOKE",
      status: "QUEUED",
      requestedAt: "2026-09-01T10:00:00Z",
      processLogUrl: `/api/v1/runs/${String(params.runId)}/log`,
    }),
  ),
  http.get("/api/v1/runs/:runId/artifacts", () => HttpResponse.json([])),
];
