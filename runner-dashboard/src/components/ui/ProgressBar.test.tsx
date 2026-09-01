import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ProgressBar } from "./ProgressBar";

describe("ProgressBar", () => {
  it("exposes its value and label to assistive tech, and clamps out-of-range values", () => {
    render(<ProgressBar value={150} label="3 of 5 tests complete" />);

    const bar = screen.getByRole("progressbar", {
      name: "3 of 5 tests complete",
    });
    expect(bar).toHaveAttribute("aria-valuenow", "100");
    expect(screen.getByText("3 of 5 tests complete")).toBeInTheDocument();
  });
});
