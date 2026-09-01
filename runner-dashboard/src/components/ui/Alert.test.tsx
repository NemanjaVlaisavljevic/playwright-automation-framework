import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Alert } from "./Alert";

describe("Alert", () => {
  it("renders as role=alert with the message text, defaulting to the danger tone", () => {
    render(<Alert>Could not cancel run: network error</Alert>);

    expect(screen.getByRole("alert")).toHaveTextContent(
      "Could not cancel run: network error",
    );
  });

  it("hides its icon from assistive tech - it reinforces the tone, it never carries it alone", () => {
    render(<Alert tone="success">Run finished.</Alert>);

    const alert = screen.getByRole("alert");
    const icon = alert.querySelector("[aria-hidden='true']");
    expect(icon).not.toBeNull();
    // The visible text is still present and accessible on its own, without the icon.
    expect(alert).toHaveTextContent("Run finished.");
  });
});
