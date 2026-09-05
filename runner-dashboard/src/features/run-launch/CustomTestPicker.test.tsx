import { QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient } from "../../app/query-client";
import { server } from "../../test/msw/server";
import { CustomTestPicker } from "./CustomTestPicker";

const API_TEST_KEY =
  "dev.vlaisanem.automation.tests.api.AuthenticationApiTest#adminCanAuthenticate";
const UI_TEST_KEY =
  "dev.vlaisanem.automation.tests.ui.HomePageTest#guestCanDiscoverBookableRooms";
const JOURNEY_TEST_KEY =
  "dev.vlaisanem.automation.tests.journey.FeaturedRoomParityTest#homepageRendersFirstThreeApiRoomsAsBookingActions";

function renderPicker(
  selectedKeys: ReadonlySet<string> = new Set(),
  onChange: (keys: ReadonlySet<string>) => void = vi.fn(),
  catalogRetryIntervalMs?: number,
) {
  return render(
    <QueryClientProvider client={createQueryClient()}>
      <CustomTestPicker
        environment="PUBLIC"
        selectedKeys={selectedKeys}
        onChange={onChange}
        {...(catalogRetryIntervalMs === undefined
          ? {}
          : { catalogRetryIntervalMs })}
      />
    </QueryClientProvider>,
  );
}

describe("CustomTestPicker", () => {
  it("shows a loading state, then every test from the real catalog endpoint", async () => {
    renderPicker();
    expect(screen.getByText("Loading tests…")).toBeInTheDocument();

    expect(
      await screen.findByText("Admin can obtain a non-empty session token"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Guest can see at least one bookable room"),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Homepage renders the first three API rooms as booking actions",
      ),
    ).toBeInTheDocument();
    expect(screen.getByText("0 tests selected")).toBeInTheDocument();
  });

  it("shows a clear error when the catalog can't be loaded", async () => {
    server.use(http.get("/api/v1/tests", () => HttpResponse.error()));
    renderPicker();

    expect(
      await screen.findByText(
        "Could not load the test catalog: Could not reach the runner service.",
      ),
    ).toBeInTheDocument();
  });

  it("recovers automatically once the catalog becomes available, without remounting", async () => {
    server.use(http.get("/api/v1/tests", () => HttpResponse.error()));

    renderPicker(new Set(), vi.fn(), 20);

    expect(
      await screen.findByText(
        "Could not load the test catalog: Could not reach the runner service.",
      ),
    ).toBeInTheDocument();

    server.resetHandlers();

    expect(
      await screen.findByText("Admin can obtain a non-empty session token"),
    ).toBeInTheDocument();
  });

  // Regression test for the review's requirement: refetchIntervalInBackground: true must actually
  // be honored, not just present in the query options - a backgrounded tab (the common way a
  // developer leaves this dashboard open across a backend restart) must still recover on its own
  // rather than only once the tab regains focus.
  it("recovers automatically even while the tab is backgrounded", async () => {
    server.use(http.get("/api/v1/tests", () => HttpResponse.error()));
    const visibilitySpy = vi
      .spyOn(document, "visibilityState", "get")
      .mockReturnValue("hidden");

    renderPicker(new Set(), vi.fn(), 20);

    expect(
      await screen.findByText(
        "Could not load the test catalog: Could not reach the runner service.",
      ),
    ).toBeInTheDocument();

    server.resetHandlers();

    expect(
      await screen.findByText("Admin can obtain a non-empty session token"),
    ).toBeInTheDocument();

    visibilitySpy.mockRestore();
  });

  it("filters by search text matched against the display name", async () => {
    const user = userEvent.setup();
    renderPicker();
    await screen.findByText("Admin can obtain a non-empty session token");

    // Deliberately not "homepage" - HomePageTest's own *testKey* also matches that
    // case-insensitively (search matches testKey too, see the next test), which would make this
    // assertion pass for the wrong reason. "first three" only appears in this one display name.
    await user.type(screen.getByLabelText("Search tests"), "first three");

    expect(
      screen.getByText(
        "Homepage renders the first three API rooms as booking actions",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByText("Admin can obtain a non-empty session token"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("Guest can see at least one bookable room"),
    ).not.toBeInTheDocument();
  });

  it("also matches search text against the raw testKey, not just the display name", async () => {
    const user = userEvent.setup();
    renderPicker();
    await screen.findByText("Admin can obtain a non-empty session token");

    // "HomePageTest" is the class name (testKey), not part of this test's own display name -
    // proves the testKey half of the search actually runs, not just displayName.
    await user.type(screen.getByLabelText("Search tests"), "HomePageTest");

    expect(
      screen.getByText("Guest can see at least one bookable room"),
    ).toBeInTheDocument();
    expect(
      screen.queryByText("Admin can obtain a non-empty session token"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText(
        "Homepage renders the first three API rooms as booking actions",
      ),
    ).not.toBeInTheDocument();
  });

  it("shows an explicit empty state when no test matches the filters", async () => {
    const user = userEvent.setup();
    renderPicker();
    await screen.findByText("Admin can obtain a non-empty session token");

    await user.type(
      screen.getByLabelText("Search tests"),
      "no such test exists",
    );

    expect(await screen.findByText("No tests match.")).toBeInTheDocument();
  });

  it("filters by layer, independently of the smoke-only checkbox", async () => {
    const user = userEvent.setup();
    renderPicker();
    await screen.findByText("Admin can obtain a non-empty session token");

    await user.selectOptions(screen.getByLabelText("Layer"), "JOURNEY");

    expect(
      screen.getByText(
        "Homepage renders the first three API rooms as booking actions",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByText("Admin can obtain a non-empty session token"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("Guest can see at least one bookable room"),
    ).not.toBeInTheDocument();
  });

  it("smoke-only narrows to tests tagged smoke, regardless of layer", async () => {
    const user = userEvent.setup();
    renderPicker();
    await screen.findByText("Admin can obtain a non-empty session token");

    await user.click(screen.getByLabelText("Smoke only"));

    // API and UI fixtures both carry `smoke`; the JOURNEY one deliberately doesn't.
    expect(
      screen.getByText("Admin can obtain a non-empty session token"),
    ).toBeInTheDocument();
    expect(
      screen.getByText("Guest can see at least one bookable room"),
    ).toBeInTheDocument();
    expect(
      screen.queryByText(
        "Homepage renders the first three API rooms as booking actions",
      ),
    ).not.toBeInTheDocument();
  });

  it("checking a test calls onChange with that test added to the existing selection", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderPicker(new Set([API_TEST_KEY]), onChange);
    const homeRow = (
      await screen.findByText("Guest can see at least one bookable room")
    ).closest("label")!;

    await user.click(homeRow.querySelector("input")!);

    expect(onChange).toHaveBeenCalledWith(new Set([API_TEST_KEY, UI_TEST_KEY]));
  });

  it("unchecking a selected test calls onChange without it", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderPicker(new Set([API_TEST_KEY, UI_TEST_KEY]), onChange);
    const homeRow = (
      await screen.findByText("Guest can see at least one bookable room")
    ).closest("label")!;

    await user.click(homeRow.querySelector("input")!);

    expect(onChange).toHaveBeenCalledWith(new Set([API_TEST_KEY]));
  });

  it("select all visible only adds the currently-filtered tests, keeping any prior selection", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderPicker(new Set([JOURNEY_TEST_KEY]), onChange);
    await screen.findByText("Admin can obtain a non-empty session token");

    await user.click(screen.getByLabelText("Smoke only"));
    await user.click(
      screen.getByRole("button", { name: "Select all visible" }),
    );

    expect(onChange).toHaveBeenCalledWith(
      new Set([JOURNEY_TEST_KEY, API_TEST_KEY, UI_TEST_KEY]),
    );
  });

  it("clear selection calls onChange with an empty set, regardless of filters", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderPicker(new Set([API_TEST_KEY, UI_TEST_KEY]), onChange);
    await waitFor(() =>
      expect(screen.getByText("2 tests selected")).toBeInTheDocument(),
    );

    await user.click(screen.getByRole("button", { name: "Clear selection" }));

    expect(onChange).toHaveBeenCalledWith(new Set());
  });
});
