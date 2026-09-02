package dev.vlaisanem.automation.runner.contract;

/**
 * What kind of file an {@link ArtifactManifestEntry} describes.
 *
 * <p>{@link #VIDEO} is part of the contract but not yet produced by any writer: a Playwright video
 * only finalizes once its {@code BrowserContext} closes, which happens well after {@code
 * TestFixture#captureFailure} runs - recording it needs a post-close manifest write (and, for a
 * passing test, deleting the otherwise-unwanted video) that hasn't been built yet. Reserved for a
 * later step, not a claim that video capture works today.
 */
public enum ArtifactType {
  SCREENSHOT,
  TRACE,
  VIDEO
}
