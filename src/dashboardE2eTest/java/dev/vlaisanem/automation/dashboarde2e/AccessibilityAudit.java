package dev.vlaisanem.automation.dashboarde2e;

import com.microsoft.playwright.Page;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runs a real axe-core accessibility audit against whatever is currently rendered on {@code page},
 * for the C4.6 automated a11y gate - this is a real, repeatable regression check against the actual
 * built bundle in a real Chromium, not a one-off manual pass. {@code axe-core} itself is a plain
 * `devDependency` of {@code runner-dashboard} (its {@code node_modules/axe-core/axe.min.js} is
 * injected directly via {@link Page#addScriptTag}), not a separate Java binding - there is no
 * maintained first-party {@code axe-core} client for Playwright Java, unlike the official
 * {@code @axe-core/playwright} package for Node.
 *
 * <p>Playwright Java's {@link Page#evaluate} deserializes a JS return value into plain Java
 * collections (nested {@code Map}/{@code List}/primitives) automatically, and awaits a returned
 * {@code Promise} on its own - both of which is all this needs: no JSON library, no extra
 * dependency, just navigating {@code axe.run()}'s own result shape directly.
 */
final class AccessibilityAudit {

  private AccessibilityAudit() {}

  private static final Path AXE_SCRIPT =
      Path.of(
          System.getProperty("dashboardE2e.dashboardDir"),
          "node_modules",
          "axe-core",
          "axe.min.js");

  /**
   * Every violation axe-core found, each as its own raw result map (see axe-core's own
   * documentation for the shape - notably {@code id}, {@code impact}, {@code description}, {@code
   * helpUrl}, and {@code nodes} listing the specific offending elements).
   */
  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> run(Page page) {
    page.addScriptTag(new Page.AddScriptTagOptions().setPath(AXE_SCRIPT));
    Object result = page.evaluate("() => axe.run().then((r) => r.violations)");
    return (List<Map<String, Object>>) result;
  }

  /**
   * A compact, human-readable summary for a failed assertion message - axe's own raw result maps
   * are too large/nested to read directly in a test failure.
   */
  @SuppressWarnings("unchecked")
  static String summarize(List<Map<String, Object>> violations) {
    StringBuilder summary = new StringBuilder();
    for (Map<String, Object> violation : violations) {
      summary
          .append("\n- [")
          .append(violation.get("impact"))
          .append("] ")
          .append(violation.get("id"))
          .append(": ")
          .append(violation.get("description"))
          .append(" (")
          .append(violation.get("helpUrl"))
          .append(")");
      List<Map<String, Object>> nodes = (List<Map<String, Object>>) violation.get("nodes");
      for (Map<String, Object> node : nodes) {
        summary.append("\n    at ").append(node.get("html"));
      }
    }
    return summary.toString();
  }
}
