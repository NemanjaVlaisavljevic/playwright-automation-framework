import { http, HttpResponse } from "msw";
import { CURRENT_SCHEMA_VERSION } from "../../domain/runner-event";

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
      eventSchemaVersion: CURRENT_SCHEMA_VERSION,
      environments: [
        {
          name: "PUBLIC",
          suites: [
            "SMOKE",
            "API",
            "UI",
            "JOURNEY",
            "REGRESSION",
            "FIXTURE",
            "CUSTOM",
          ],
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
      selectedTests: [],
    }),
  ),
  http.get("/api/v1/runs/:runId/artifacts", () => HttpResponse.json([])),
  http.get("/api/v1/tests", () =>
    HttpResponse.json({
      tests: [
        {
          testKey:
            "dev.vlaisanem.automation.tests.api.AuthenticationApiTest#adminCanAuthenticate",
          displayName: "Admin can obtain a non-empty session token",
          category: "API",
          tags: ["smoke", "auth", "regression", "api", "read-only"],
        },
        {
          testKey:
            "dev.vlaisanem.automation.tests.ui.HomePageTest#guestCanDiscoverBookableRooms",
          displayName: "Guest can see at least one bookable room",
          category: "UI",
          tags: ["smoke", "regression", "ui", "room", "read-only"],
        },
        {
          testKey:
            "dev.vlaisanem.automation.tests.journey.FeaturedRoomParityTest#homepageRendersFirstThreeApiRoomsAsBookingActions",
          displayName:
            "Homepage renders the first three API rooms as booking actions",
          category: "JOURNEY",
          tags: ["regression", "journey", "room", "read-only"],
        },
      ],
    }),
  ),
];
