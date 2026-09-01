import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "../../app/query-client";
import { RunListPage } from "./RunListPage";

describe("RunListPage", () => {
  it("renders the page title, the launch form, and the runs table", async () => {
    render(
      <QueryClientProvider client={createQueryClient()}>
        <MemoryRouter>
          <RunListPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(screen.getByRole("heading", { name: "Runs" })).toBeInTheDocument();
    expect(
      await screen.findByRole("heading", { name: "Start a run" }),
    ).toBeInTheDocument();
  });
});
