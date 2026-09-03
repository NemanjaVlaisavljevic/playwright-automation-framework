import type { ArtifactSummaryResponse } from "../api/runner-api";

export function artifactTypeLabel(
  type: ArtifactSummaryResponse["type"],
): string {
  switch (type) {
    case "SCREENSHOT":
      return "Screenshot";
    case "TRACE":
      return "Trace";
    case "VIDEO":
      return "Video";
  }
}
