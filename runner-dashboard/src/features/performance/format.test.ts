import { describe, expect, it } from "vitest";
import { formatMs, formatUtilization, resultStatus } from "./format";

describe("resultStatus", () => {
  it("maps true/false/null to PASSED/REGRESSION/OBSERVED ONLY", () => {
    expect(resultStatus(true)).toBe("PASSED");
    expect(resultStatus(false)).toBe("REGRESSION");
    expect(resultStatus(null)).toBe("OBSERVED ONLY");
  });
});

describe("formatMs", () => {
  it("renders one decimal place", () => {
    expect(formatMs(5)).toBe("5.0 ms");
    expect(formatMs(71.7)).toBe("71.7 ms");
    expect(formatMs(0)).toBe("0.0 ms");
  });
});

describe("formatUtilization", () => {
  it("renders — when there is no locked limit", () => {
    expect(formatUtilization(5, null)).toBe("—");
  });

  it("renders a rounded percentage of the limit", () => {
    expect(formatUtilization(50, 250)).toBe("20%");
    expect(formatUtilization(71.7, 250)).toBe("29%");
  });

  it("can exceed 100% for a regressed metric", () => {
    expect(formatUtilization(70, 50)).toBe("140%");
  });
});
