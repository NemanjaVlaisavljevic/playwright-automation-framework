package dev.vlaisanem.automation.dashboarde2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * The screenshot/trace/video capture-on-failure mechanism shared by every dashboard-e2e test class:
 * {@link DashboardE2eEnvironment} (a JUnit extension owning the whole test lifecycle) and {@link
 * BackendUnavailableE2eTest} (which manages its own page/context directly, see its own Javadoc for
 * why it can't share the extension) both call these same static methods so a failure in either
 * produces the same {@code build/dashboard-e2e-failures/<test>/} evidence.
 */
final class BrowserFailureArtifacts {

  private BrowserFailureArtifacts() {}

  static Path directoryFor(Class<?> testClass, String testMethodName) {
    return Path.of(
        "build", "dashboard-e2e-failures", testClass.getSimpleName() + "-" + testMethodName);
  }

  /**
   * A stale directory from a previous FAILING run of this same test must not survive a subsequent
   * PASSING run - otherwise its old screenshot/trace/video could be mistaken for fresh evidence of
   * a failure that didn't actually happen this time.
   */
  static void clearStale(Path failureDir) {
    deleteRecursively(failureDir);
  }

  static void startTracing(BrowserContext context) {
    context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
  }

  /**
   * Screenshots the page and stops the trace (saved only if {@code failed}) - call this BEFORE
   * anything else that could itself change what's on screen (e.g. a best-effort cleanup call), and
   * BEFORE closing {@code context}/{@code page}, so the evidence reflects the actual failure.
   */
  static void captureBeforeClose(
      Path failureDir, boolean failed, Page page, BrowserContext context) {
    if (failed) {
      safely(() -> Files.createDirectories(failureDir));
      safely(
          () ->
              page.screenshot(
                  new Page.ScreenshotOptions()
                      .setPath(failureDir.resolve("screenshot.png"))
                      .setFullPage(true)));
      safely(
          () ->
              context
                  .tracing()
                  .stop(new Tracing.StopOptions().setPath(failureDir.resolve("trace.zip"))));
    } else {
      safely(() -> context.tracing().stop());
    }
  }

  /**
   * Playwright only finalizes a context's recorded video once the context (or, for a
   * default-context page, the page itself) actually closes - call this only after that close, with
   * the same {@code page} passed to {@link #captureBeforeClose}.
   */
  static void saveVideoIfFailed(Path failureDir, boolean failed, Page page) {
    if (!failed) {
      return;
    }
    var video = page.video();
    if (video != null) {
      safely(() -> Files.move(video.path(), failureDir.resolve("video.webm")));
    }
  }

  /**
   * A best-effort per-test cleanup step (capturing an artifact, cancelling a leftover run) must
   * never itself hide the test's own real failure by throwing over it, and one such step failing
   * must never prevent the next one from still running.
   */
  static void safely(ThrowingRunnable action) {
    try {
      action.run();
    } catch (Exception e) {
      System.err.println("dashboardE2eTest: cleanup step failed: " + e);
    }
  }

  @FunctionalInterface
  interface ThrowingRunnable {
    void run() throws Exception;
  }

  static void deleteRecursively(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.delete(path);
                } catch (IOException e) {
                  // Best-effort cleanup of a scratch temp directory - not worth failing a test run
                  // over a file the OS is still briefly holding onto.
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
