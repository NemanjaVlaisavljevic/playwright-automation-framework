import { render, screen } from "@testing-library/react";
import { createMemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { App } from "./App";
import { appRoutes } from "./router";

describe("App", () => {
  it("redirects the root route to /runs", async () => {
    const router = createMemoryRouter(appRoutes, { initialEntries: ["/"] });

    render(<App router={router} />);

    expect(
      await screen.findByRole("heading", { name: "Runs" }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/runs");
  });

  it("renders a run details page for /runs/:runId", async () => {
    const router = createMemoryRouter(appRoutes, {
      initialEntries: ["/runs/run-1"],
    });

    render(<App router={router} />);

    expect(
      await screen.findByRole("heading", { name: "Run run-1" }),
    ).toBeInTheDocument();
  });
});
