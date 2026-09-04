import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CopyButton } from "./CopyButton";

function mockClipboard() {
  const writeText = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", {
    value: { writeText },
    configurable: true,
  });
  return writeText;
}

describe("CopyButton", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  /**
   * Regression test (review finding, P3): a static `aria-label` overrides the button's own visible
   * text for assistive tech, so the "Copied!" text change was otherwise invisible to a screen
   * reader - the label alone made the confirmation silent. A separate `aria-live="polite"` region
   * announces it instead, without changing the button's own accessible name (it must keep saying
   * what it copies, not "Copied!", which would drop the very context that makes it findable).
   */
  it("announces a successful copy via a live region, without changing the button's own accessible name", async () => {
    mockClipboard();
    render(
      <CopyButton
        text="https://example.test/runs/1"
        ariaLabel="Copy link to test aTest()"
      />,
    );

    const button = screen.getByRole("button", {
      name: "Copy link to test aTest()",
    });
    await userEvent.click(button);

    expect(await screen.findByText("Copied to clipboard.")).toBeInTheDocument();
    // The button's own accessible name never changes to "Copied!" - it keeps describing what it
    // does, exactly as before the click.
    expect(
      screen.getByRole("button", { name: "Copy link to test aTest()" }),
    ).toBeInTheDocument();
  });

  it("announces nothing before any copy has happened", () => {
    render(<CopyButton text="https://example.test/runs/1" />);

    expect(screen.queryByText("Copied to clipboard.")).not.toBeInTheDocument();
  });
});
