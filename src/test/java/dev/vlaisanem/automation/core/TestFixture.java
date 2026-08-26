package dev.vlaisanem.automation.core;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import dev.vlaisanem.automation.api.ApiContextFactory;
import dev.vlaisanem.automation.config.TestConfig;
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

  void captureFailure(ExtensionContext context) {
    if (browserContext == null) {
      return;
    }
    Path testDirectory = config.artifactsDirectory().resolve(artifactName(context));
    try {
      Files.createDirectories(testDirectory);
      if (page != null && !page.isClosed()) {
        Path screenshot = testDirectory.resolve("failure.png");
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshot).setFullPage(true));
        attach("Failure screenshot", "image/png", screenshot, ".png");
      }
      if (traceRunning) {
        Path trace = testDirectory.resolve("trace.zip");
        browserContext.tracing().stop(new Tracing.StopOptions().setPath(trace));
        traceRunning = false;
        attach("Playwright trace", "application/zip", trace, ".zip");
      }
    } catch (RuntimeException | IOException exception) {
      LOGGER.warn(
          "Could not capture all failure artifacts for {}", context.getDisplayName(), exception);
    }
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

  private static void attach(String name, String mediaType, Path path, String extension)
      throws IOException {
    try (InputStream input = Files.newInputStream(path)) {
      Allure.addAttachment(name, mediaType, input, extension);
    }
  }
}
