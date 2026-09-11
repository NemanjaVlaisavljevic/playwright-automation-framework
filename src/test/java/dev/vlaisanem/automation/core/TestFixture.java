package dev.vlaisanem.automation.core;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import dev.vlaisanem.automation.api.ApiContextFactory;
import dev.vlaisanem.automation.config.TestConfig;
import dev.vlaisanem.automation.runner.contract.ArtifactType;
import io.qameta.allure.Allure;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TestFixture implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(TestFixture.class);

  private final TestConfig config;
  private final RuntimeRegistry registry;
  private BrowserContext browserContext;
  private Page page;
  private APIRequestContext apiRequestContext;
  private ApiContextFactory apiContextFactory;
  private Steps steps;
  private boolean traceRunning;

  TestFixture(TestConfig config, RuntimeRegistry registry) {
    this.config = config;
    this.registry = registry;
  }

  TestConfig config() {
    return config;
  }

  Page page() {
    if (page == null) {
      Browser.NewContextOptions options =
          new Browser.NewContextOptions()
              .setBaseURL(config.baseUrl())
              .setLocale("en-GB")
              .setTimezoneId("Europe/London")
              .setViewportSize(1440, 900);
      // Video is not disk-protected like screenshots/traces: Playwright writes it directly on
      // context close, with no manifest entry or size cap. Dashboard-launched runs force
      // RECORD_VIDEO=false, so this only applies to a standalone local invocation.
      if (config.recordVideo()) {
        options.setRecordVideoDir(config.artifactsDirectory().resolve("videos"));
      }
      browserContext = registry.engine(config).browser().newContext(options);
      browserContext.setDefaultTimeout(config.actionTimeout().toMillis());
      browserContext.setDefaultNavigationTimeout(config.navigationTimeout().toMillis());
      if (config.tracing()) {
        browserContext
            .tracing()
            .start(
                new Tracing.StartOptions()
                    .setScreenshots(true)
                    .setSnapshots(true)
                    .setSources(true));
        traceRunning = true;
      }
      page = browserContext.newPage();
    }
    return page;
  }

  APIRequestContext api() {
    if (apiRequestContext == null) {
      apiRequestContext = apiContexts().anonymous();
    }
    return apiRequestContext;
  }

  ApiContextFactory apiContexts() {
    if (apiContextFactory == null) {
      apiContextFactory =
          new ApiContextFactory(registry.engine(config).playwright().request(), config);
    }
    return apiContextFactory;
  }

  Steps steps(ExtensionContext context) {
    if (steps == null) {
      steps = new Steps(config.runId(), context.getUniqueId(), context.getDisplayName());
    }
    return steps;
  }

  /**
   * Each step is an independent best-effort attempt (see {@link #safely}): a failure in one (Allure
   * I/O, a manifest write) never prevents the others or touches the test's own failure. The
   * manifest entry is recorded as soon as the artifact file exists, before Allure sees it.
   */
  void captureFailure(ExtensionContext context) {
    if (browserContext == null) {
      return;
    }
    Path testDirectory = config.artifactsDirectory().resolve(artifactName(context));
    safely(context, "create the artifacts directory", () -> Files.createDirectories(testDirectory));

    boolean pageOpen =
        page != null
            && safelyGet(context, "check whether the page is open", () -> !page.isClosed(), false);
    if (pageOpen) {
      Path screenshot = testDirectory.resolve("failure.png");
      // Captured to memory first so an oversized screenshot is never written to disk at all
      // (tracing below has no in-memory alternative, so it can only be enforced after the fact).
      boolean captured =
          safelyGet(
              context,
              "capture a failure screenshot",
              () -> {
                byte[] bytes = page.screenshot(new Page.ScreenshotOptions().setFullPage(true));
                if (bytes.length > config.artifactMaxBytes()) {
                  LOGGER.warn(
                      "Skipping failure screenshot for {} ({} bytes) - exceeds the configured {}"
                          + "-byte per-artifact limit",
                      context.getDisplayName(),
                      bytes.length,
                      config.artifactMaxBytes());
                  return false;
                }
                try {
                  Files.write(screenshot, bytes);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
                return true;
              },
              false);
      if (captured) {
        boolean recorded =
            safelyGet(
                context,
                "record the screenshot artifact manifest entry",
                () -> recordArtifact(context, ArtifactType.SCREENSHOT, screenshot, "image/png"),
                false);
        if (recorded && config.allureAttachmentsEnabled()) {
          safely(
              context,
              "attach the failure screenshot to Allure",
              () -> attach("Failure screenshot", "image/png", screenshot, ".png"));
        }
      }
    }

    if (traceRunning) {
      Path trace = testDirectory.resolve("trace.zip");
      // tracing().stop() is one-shot; clear the flag even on failure so it's never retried.
      traceRunning = false;
      // Trace streams straight to disk with no in-memory alternative, so this pre-flight check is
      // the only preventive guard; real enforcement happens after the fact in recordArtifact.
      boolean captured =
          hasEnoughFreeSpaceForTraceCapture(context)
              && safely(
                  context,
                  "stop the Playwright trace",
                  () -> browserContext.tracing().stop(new Tracing.StopOptions().setPath(trace)));
      if (captured) {
        boolean recorded =
            safelyGet(
                context,
                "record the trace artifact manifest entry",
                () -> recordArtifact(context, ArtifactType.TRACE, trace, "application/zip"),
                false);
        if (recorded && config.allureAttachmentsEnabled()) {
          safely(
              context,
              "attach the Playwright trace to Allure",
              () -> attach("Playwright trace", "application/zip", trace, ".zip"));
        }
      }
    }
  }

  /**
   * Fail-closed: a probe that can't determine free space must skip the capture, not assume there's
   * room. Package-private so {@code TestFixtureTest} can call it directly.
   */
  boolean hasEnoughFreeSpaceForTraceCapture(ExtensionContext context) {
    return safelyGet(
        context,
        "check free disk space before finalizing a trace",
        () -> {
          long usable;
          try {
            FileStore store = Files.getFileStore(config.artifactsDirectory());
            usable = store.getUsableSpace();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          if (usable < config.traceCaptureMinFreeBytes()) {
            LOGGER.warn(
                "Skipping trace capture for {} - only {} bytes free, below the configured {}-byte"
                    + " floor",
                context.getDisplayName(),
                usable,
                config.traceCaptureMinFreeBytes());
            return false;
          }
          return true;
        },
        false);
  }

  private boolean safely(ExtensionContext context, String step, ThrowingRunnable action) {
    try {
      action.run();
      return true;
    } catch (RuntimeException | IOException exception) {
      LOGGER.warn("Could not {} for {}", step, context.getDisplayName(), exception);
      return false;
    }
  }

  /**
   * Same best-effort contract as {@link #safely}, for a step whose result feeds a later decision -
   * e.g. {@code page.isClosed()} can throw if the browser already crashed, without aborting the
   * independent trace-capture step below it.
   */
  private <T> T safelyGet(
      ExtensionContext context, String step, ThrowingSupplier<T> action, T fallback) {
    try {
      return action.get();
    } catch (RuntimeException exception) {
      LOGGER.warn("Could not {} for {}", step, context.getDisplayName(), exception);
      return fallback;
    }
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws IOException;
  }

  @FunctionalInterface
  private interface ThrowingSupplier<T> {
    T get();
  }

  @Override
  public void close() {
    if (apiContextFactory != null) {
      apiContextFactory.close();
    }
    if (browserContext != null) {
      try {
        if (traceRunning) {
          browserContext.tracing().stop();
          traceRunning = false;
        }
      } finally {
        browserContext.close();
      }
    }
  }

  private static String artifactName(ExtensionContext context) {
    String readable =
        context.getRequiredTestClass().getSimpleName() + "-" + context.getDisplayName();
    String slug = readable.replaceAll("[^a-zA-Z0-9._-]+", "-").replaceAll("-+", "-");
    return slug + "-" + Integer.toHexString(context.getUniqueId().hashCode());
  }

  /**
   * @return {@code true} if the artifact was actually recorded in the manifest.
   */
  private boolean recordArtifact(
      ExtensionContext context, ArtifactType type, Path artifactFile, String mediaType) {
    // Only the step whose failure is the test's actual execution exception counts, not merely the
    // last step that failed (see Steps#stepIdForFailure).
    String stepId =
        steps == null ? null : steps.stepIdForFailure(context.getExecutionException().orElse(null));
    try {
      return ArtifactManifestWriter.record(
          config.artifactsDirectory(),
          config.runId(),
          context.getUniqueId(),
          context.getDisplayName(),
          stepId,
          type,
          artifactFile,
          mediaType,
          config.artifactMaxBytes(),
          config.runMaxTotalArtifactBytes(),
          config.manifestMaxBytes());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void attach(String name, String mediaType, Path path, String extension)
      throws IOException {
    try (InputStream input = Files.newInputStream(path)) {
      Allure.addAttachment(name, mediaType, input, extension);
    }
  }
}
