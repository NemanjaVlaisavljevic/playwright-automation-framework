import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { StatusBadge } from "./StatusBadge";

describe("StatusBadge", () => {
  it.each([
    "QUEUED",
    "RUNNING",
    "SUCCEEDED",
    "PASSED",
    "FAILED",
    "CANCELLED",
    "ABORTED",
    "SKIPPED",
  ] as const)(
    "always renders the status name as visible text (%s), never color alone",
    (status) => {
      render(<StatusBadge status={status} />);

      expect(screen.getByText(status)).toBeInTheDocument();
    },
  );
});
