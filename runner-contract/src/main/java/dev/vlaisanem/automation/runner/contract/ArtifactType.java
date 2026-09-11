package dev.vlaisanem.automation.runner.contract;

/**
 * What kind of file an {@link ArtifactManifestEntry} describes.
 *
 * <p>{@link #VIDEO} is part of the contract but not yet produced by any writer: a Playwright video
 * only finalizes once its {@code BrowserContext} closes, after capture runs today.
 */
public enum ArtifactType {
  SCREENSHOT,
  TRACE,
  VIDEO
}
