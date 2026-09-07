import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/query-client";
import { server } from "../../test/msw/server";
import { AuthControls } from "./AuthControls";

function renderAuthControls() {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <AuthControls />
    </QueryClientProvider>,
  );
}

describe("AuthControls", () => {
  it("renders nothing when GitHub OAuth2 is not configured at all (the permissive chain)", async () => {
    server.use(
      http.get("/api/v1/auth/me", () =>
        HttpResponse.json({
          authenticationRequired: false,
          canManageRuns: true,
          authenticated: false,
        }),
      ),
    );

    renderAuthControls();

    await waitFor(() =>
      expect(
        screen.queryByRole("link", { name: "Log in with GitHub" }),
      ).not.toBeInTheDocument(),
    );
    expect(
      screen.queryByRole("button", { name: "Log out" }),
    ).not.toBeInTheDocument();
  });

  it("shows a login link when authentication is required but the caller is anonymous", async () => {
    server.use(
      http.get("/api/v1/auth/me", () =>
        HttpResponse.json({
          authenticationRequired: true,
          canManageRuns: false,
          authenticated: false,
        }),
      ),
    );

    renderAuthControls();

    const link = await screen.findByRole("link", {
      name: "Log in with GitHub",
    });
    expect(link).toHaveAttribute(
      "href",
      "/api/v1/auth/oauth2/authorization/github",
    );
  });

  it("shows the logged-in admin's login and a Logout button", async () => {
    renderAuthControls();

    expect(await screen.findByText("octocat")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Log out" })).toBeInTheDocument();
  });

  it("primes the CSRF token once on mount", async () => {
    const csrfCalls = vi.fn();
    server.use(
      http.get("/api/v1/auth/csrf", () => {
        csrfCalls();
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderAuthControls();

    await waitFor(() => expect(csrfCalls).toHaveBeenCalledOnce());
  });

  it("logs out and returns to the login link, re-priming CSRF afterwards", async () => {
    let loggedOut = false;
    const csrfCalls = vi.fn();
    server.use(
      http.get("/api/v1/auth/me", () =>
        HttpResponse.json(
          loggedOut
            ? {
                authenticationRequired: true,
                canManageRuns: false,
                authenticated: false,
              }
            : {
                authenticationRequired: true,
                canManageRuns: true,
                authenticated: true,
                login: "octocat",
                avatarUrl: "https://example.invalid/avatar.png",
              },
        ),
      ),
      http.post("/api/v1/auth/logout", () => {
        loggedOut = true;
        return new HttpResponse(null, { status: 204 });
      }),
      http.get("/api/v1/auth/csrf", () => {
        csrfCalls();
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderAuthControls();
    await screen.findByRole("button", { name: "Log out" });
    const csrfCallsBeforeLogout = csrfCalls.mock.calls.length;

    await userEvent.click(screen.getByRole("button", { name: "Log out" }));

    await screen.findByRole("link", { name: "Log in with GitHub" });
    expect(csrfCalls.mock.calls.length).toBeGreaterThan(csrfCallsBeforeLogout);
  });
});
