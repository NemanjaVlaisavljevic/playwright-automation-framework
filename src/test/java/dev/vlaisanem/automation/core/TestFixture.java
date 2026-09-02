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

  /**
   * Every step below is its own independent best-effort attempt (see {@link #safely}) - a failure
   * in one (an Allure I/O hiccup, a manifest write failure) must never prevent any of the others,
   * and never touches the test's own real failure ({@code context}'s execution exception, which
   * this method never reads or alters). The manifest entry for an artifact is recorded as soon as
   * the file itself exists, before Allure ever sees it - Allure attachment failing must not make an
   * already-successfully-written screenshot/trace invisible to the manifest (and therefore the
   * dashboard) too.
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
      boolean captured =
          safely(
              context,
              "capture a failure screenshot",
              () ->
                  page.screenshot(
                      new Page.ScreenshotOptions().setPath(screenshot).setFullPage(true)));
      if (captured) {
        safely(
            context,
            "record the screenshot artifact manifest entry",
            () -> recordArtifact(context, ArtifactType.SCREENSHOT, screenshot, "image/png"));
        safely(
            context,
            "attach the failure screenshot to Allure",
            () -> attach("Failure screenshot", "image/png", screenshot, ".png"));
      }
    }

    if (traceRunning) {
      Path trace = testDirectory.resolve("trace.zip");
      // Cleared unconditionally, not only on success: tracing().stop() is a one-shot action either
      // way - even a failed attempt must not be retried (e.g. from close()) or left permanently
      // "still running".
      traceRunning = false;
      boolean captured =
          safely(
              context,
              "stop the Playwright trace",
              () -> browserContext.tracing().stop(new Tracing.StopOptions().setPath(trace)));
      if (captured) {
        safely(
            context,
            "record the trace artifact manifest entry",
            () -> recordArtifact(context, ArtifactType.TRACE, trace, "application/zip"));
        safely(
            context,
            "attach the Playwright trace to Allure",
            () -> attach("Playwright trace", "application/zip", trace, ".zip"));
      }
    }
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
   * Same best-effort contract as {@link #safely}, for a step whose own result (not just whether it
   * succeeded) feeds a later decision - {@code page.isClosed()} itself can throw if the underlying
   * driver/browser already crashed, and that must not abort capture entirely (in particular, must
   * not skip the completely independent trace-capture step below it).
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

  private void recordArtifact(
      ExtensionContext context, ArtifactType type, Path artifactFile, String mediaType)
      throws IOException {
    ArtifactManifestWriter.record(
        config.artifactsDirectory(),
        config.runId(),
        context.getUniqueId(),
        context.getDisplayName(),
        type,
        artifactFile,
        mediaType);
  }

  private static void attach(String name, String mediaType, Path path, String extension)
      throws IOException {
    try (InputStream input = Files.newInputStream(path)) {
      Allure.addAttachment(name, mediaType, input, extension);
    }
  }
}
