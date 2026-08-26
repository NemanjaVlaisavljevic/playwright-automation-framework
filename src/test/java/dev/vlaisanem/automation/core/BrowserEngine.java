package dev.vlaisanem.automation.core;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import dev.vlaisanem.automation.config.BrowserName;
import dev.vlaisanem.automation.config.TestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BrowserEngine implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(BrowserEngine.class);

  private final Playwright playwright;
  private final TestConfig config;
  private Browser browser;

  BrowserEngine(TestConfig config) {
    this.config = config;
    playwright = Playwright.create();
  }

  Playwright playwright() {
    return playwright;
  }

  Browser browser() {
    if (browser == null) {
      BrowserType browserType = browserType(playwright, config.browser());
      browser =
          browserType.launch(
              new BrowserType.LaunchOptions().setHeadless(config.headless()).setTimeout(30_000));
      LOGGER.info(
          "Started {} browser for worker {}", config.browser(), Thread.currentThread().getName());
    }
    return browser;
  }

  @Override
  public void close() {
    try {
      if (browser != null) {
        browser.close();
      }
    } finally {
      playwright.close();
    }
  }

  private static BrowserType browserType(Playwright playwright, BrowserName browserName) {
    return switch (browserName) {
      case CHROMIUM -> playwright.chromium();
      case FIREFOX -> playwright.firefox();
      case WEBKIT -> playwright.webkit();
    };
  }
}
