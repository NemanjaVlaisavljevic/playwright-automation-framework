import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/query-client";
import { server } from "../../test/msw/server";
import { RunLaunchForm } from "./RunLaunchForm";

function renderForm(
  props: Partial<{ capabilitiesRetryIntervalMs: number }> = {},
) {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <MemoryRouter initialEntries={["/runs"]}>
        <Routes>
          <Route path="/runs" element={<RunLaunchForm {...props} />} />
          <Route path="/runs/:runId" element={<p>Run details placeholder</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const runResponse = (overrides: Partial<Record<string, unknown>> = {}) => ({
  runId: "run-1",
  environment: "PUBLIC",
  suite: "SMOKE",
  status: "QUEUED",
  requestedAt: "2026-09-01T10:00:00Z",
  processLogUrl: "/api/v1/runs/run-1/log",
  ...overrides,
});

describe("RunLaunchForm", () => {
  it("shows a loading state while capabilities are pending", () => {
    renderForm();
    expect(screen.getByText("Loading capabilities…")).toBeInTheDocument();
  });

  it("shows a clear error when capabilities can't be loaded", async () => {
    server.use(http.get("/api/v1/capabilities", () => HttpResponse.error()));

    renderForm();

    expect(
      await screen.findByText(
        "Could not load capabilities: Could not reach the runner service.",
      ),
    ).toBeInTheDocument();
  });

  it("recovers automatically once capabilities become available, without remounting", async () => {
    server.use(http.get("/api/v1/capabilities", () => HttpResponse.error()));

    renderForm({ capabilitiesRetryIntervalMs: 20 });

    expect(
      await screen.findByText(
        "Could not load capabilities: Could not reach the runner service.",
      ),
    ).toBeInTheDocument();

    server.resetHandlers();

    expect(
      await screen.findByRole("button", { name: "Run" }),
    ).toBeInTheDocument();
  });

  it("populates the suite options from capabilities and submits the selected combination", async () => {
    const user = userEvent.setup();
    let capturedBody: unknown;
    server.use(
      http.post("/api/v1/runs", async ({ request }) => {
        capturedBody = await request.json();
        return HttpResponse.json(runResponse(), { status: 202 });
      }),
    );

    renderForm();

    const suiteSelect = await screen.findByLabelText("Suite");
    expect(
      Array.from(suiteSelect.querySelectorAll("option")).map(
        (option) => option.textContent,
      ),
    ).toEqual(["SMOKE", "API", "UI", "JOURNEY", "REGRESSION"]);

    await user.selectOptions(suiteSelect, "API");
    await user.click(screen.getByRole("button", { name: "Run" }));

    await waitFor(() => {
      expect(capturedBody).toEqual({ environment: "PUBLIC", suite: "API" });
    });
    expect(
      await screen.findByText("Run details placeholder"),
    ).toBeInTheDocument();
  });

  it("disables the submit button while a launch is in flight (no double submit)", async () => {
    const user = userEvent.setup();
    let resolveCreate: (() => void) | undefined;
    server.use(
      http.post(
        "/api/v1/runs",
        () =>
          new Promise<Response>((resolve) => {
            resolveCreate = () =>
              resolve(
                HttpResponse.json(runResponse(), {
                  status: 202,
                }) as unknown as Response,
              );
          }),
      ),
    );

    renderForm();
    await screen.findByRole("button", { name: "Run" });

    await user.click(screen.getByRole("button", { name: "Run" }));

    const startingButton = await screen.findByRole("button", {
      name: "Starting…",
    });
    expect(startingButton).toBeDisabled();

    resolveCreate?.();
    await screen.findByText("Run details placeholder");
  });

  it("shows the backend's detail message for a 400 response", async () => {
    const user = userEvent.setup();
    server.use(
      http.post("/api/v1/runs", () =>
        HttpResponse.json(
          {
            title: "Bad Request",
            status: 400,
            detail: "SMOKE is not allowed for environment PUBLIC",
            instance: "/api/v1/runs",
          },
          { status: 400 },
        ),
      ),
    );

    renderForm();
    await user.click(await screen.findByRole("button", { name: "Run" }));

    expect(
      await screen.findByText("SMOKE is not allowed for environment PUBLIC"),
    ).toBeInTheDocument();
  });

  it("shows a busy message for a 503 response", async () => {
    const user = userEvent.setup();
    server.use(
      http.post("/api/v1/runs", () => new HttpResponse(null, { status: 503 })),
    );

    renderForm();
    await user.click(await screen.findByRole("button", { name: "Run" }));

    expect(
      await screen.findByText(
        "The runner is busy or temporarily unavailable. Try again shortly.",
      ),
    ).toBeInTheDocument();
  });

  it("shows a generic message for an unexpected 500 response", async () => {
    const user = userEvent.setup();
    server.use(
      http.post("/api/v1/runs", () => new HttpResponse(null, { status: 500 })),
    );

    renderForm();
    await user.click(await screen.findByRole("button", { name: "Run" }));

    expect(
      await screen.findByText(
        "An unexpected error occurred while starting the run.",
      ),
    ).toBeInTheDocument();
  });
});
