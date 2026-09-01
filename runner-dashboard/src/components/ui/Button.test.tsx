import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Button } from "./Button";

describe("Button", () => {
  it("renders its label and calls onClick when clicked", async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Cancel</Button>);

    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(onClick).toHaveBeenCalledOnce();
  });

  it("defaults to type=button, so it never accidentally submits a surrounding form", () => {
    render(<Button>Save</Button>);

    expect(screen.getByRole("button", { name: "Save" })).toHaveAttribute(
      "type",
      "button",
    );
  });

  it("is disabled and non-interactive when disabled is set", async () => {
    const onClick = vi.fn();
    render(
      <Button disabled onClick={onClick}>
        Cancel
      </Button>,
    );

    const button = screen.getByRole("button", { name: "Cancel" });
    expect(button).toBeDisabled();
    await userEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });
});
