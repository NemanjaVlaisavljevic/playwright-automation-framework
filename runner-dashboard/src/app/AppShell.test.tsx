import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "./query-client";
import { server } from "../test/msw/server";
import { AppShell } from "./AppShell";

// A real (short) interval rather than fake timers: AppShell takes this as a prop specifically so
// the recovery test below can observe real polling behavior quickly and deterministically.
const FAST_HEALTH_INTERVAL = 20;

function renderShell(healthRefetchIntervalMs?: number) {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={["/runs"]}>
        <Routes>
          <Route
            element={
              <AppShell
                {...(healthRefetchIntervalMs !== undefined
                  ? { healthRefetchIntervalMs }
                  : {})}
              />
            }
          >
            <Route path="/runs" element={<p>Page content</p>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("AppShell", () => {
  it("shows the runner service health once loaded", async () => {
    renderShell();

    expect(await screen.findByText("Runner service: UP")).toBeInTheDocument();
  });

  it("shows a clear error when the backend is unavailable", async () => {
    server.use(http.get("/actuator/health", () => HttpResponse.error()));

    renderShell();

    expect(
      await screen.findByText(
        "Runner service unavailable: Could not reach the runner service.",
      ),
    ).toBeInTheDocument();
  });

  it("recovers automatically once the backend comes back, without remounting", async () => {
    server.use(http.get("/actuator/health", () => HttpResponse.error()));

    renderShell(FAST_HEALTH_INTERVAL);

    expect(
      await screen.findByText(
        "Runner service unavailable: Could not reach the runner service.",
      ),
    ).toBeInTheDocument();

    // The backend recovers - restore the default (success) handlers from src/test/msw/handlers.ts.
    // Nothing re-renders or remounts here; only the next poll picks this up.
    server.resetHandlers();

    expect(await screen.findByText("Runner service: UP")).toBeInTheDocument();
  });

  it("renders the routed page content alongside a Runs nav link", () => {
    renderShell();

    expect(screen.getByText("Page content")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Runs" })).toHaveAttribute(
      "aria-current",
      "page",
    );
  });
});
