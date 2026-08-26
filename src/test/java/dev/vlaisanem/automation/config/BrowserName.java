package dev.vlaisanem.automation.config;

import java.util.Locale;

public enum BrowserName {
  CHROMIUM,
  FIREFOX,
  WEBKIT;

  public static BrowserName from(String value) {
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "Unsupported browser '" + value + "'. Use chromium, firefox, or webkit.", exception);
    }
  }
}
