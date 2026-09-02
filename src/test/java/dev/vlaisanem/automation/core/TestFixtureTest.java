package dev.vlaisanem.automation.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import dev.vlaisanem.automation.config.BrowserName;
import dev.vlaisanem.automation.config.TestConfig;
import dev.vlaisanem.automation.runner.contract.ArtifactManifestEntry;
import dev.vlaisanem.automation.runner.contract.ArtifactType;
import dev.vlaisanem.automation.support.JsonSupport;
import io.qameta.allure.Allure;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * Exercises {@link TestFixture#captureFailure} against mocked Playwright interfaces (real ones
 * would need an actual browser) - specifically the independent-best-effort-step guarantee added in
 * response to a review's finding: a failure in any one artifact-capture step (screenshot, its
 * manifest entry, its Allure attachment, and the same for the trace) must never prevent any other
 * step, and must never itself become a new reported failure that could mask the test's own real
 * one.
 */
class TestFixtureTest {

  @Test
  void aScreenshotCaptureFailureDoesNotPreventTraceCapture(@TempDir Path artifactsDir)
      throws Exception {
    Page page = mock(Page.class);
    when(page.isClosed()).thenReturn(false);
    doThrow(new RuntimeException("simulated screenshot failure"))
        .when(page)
        .screenshot(any(Page.ScreenshotOptions.class));
    BrowserContext browserContext = browserContextWithWorkingTracing();
    TestFixture fixture = fixtureWith(artifactsDir, browserContext, page, true);

    fixture.captureFailure(context("someTest"));

    List<ArtifactManifestEntry> entries = readManifest(artifactsDir);
    assertThat(entries).extracting(ArtifactManifestEntry::type).containsExactly(ArtifactType.TRACE);
  }

  @Test
  void aPageIsClosedFailureDoesNotPreventTraceCapture(@TempDir Path artifactsDir) throws Exception {
    Page page = mock(Page.class);
    doThrow(new RuntimeException("simulated driver crash")).when(page).isClosed();
    BrowserContext browserContext = browserContextWithWorkingTracing();
    TestFixture fixture = fixtureWith(artifactsDir, browserContext, page, true);

    assertThatCode(() -> fixture.captureFailure(context("someTest"))).doesNotThrowAnyException();

    assertThat(expectedTestDirectory(artifactsDir, "someTest").resolve("trace.zip")).exists();
    List<ArtifactManifestEntry> entries = readManifest(artifactsDir);
    assertThat(entries).extracting(ArtifactManifestEntry::type).containsExactly(ArtifactType.TRACE);
  }

  @Test
  void aScreenshotManifestFailureDoesNotPreventTraceCapture(@TempDir Path artifactsDir)
      throws Exception {
    // A directory sitting where manifest.jsonl needs to be a file - every manifest write (for
    // either artifact) fails, but that must not stop the trace file itself from being captured.
    Files.createDirectories(artifactsDir.resolve("manifest.jsonl"));
    Page page = workingScreenshotPage();
    BrowserContext browserContext = browserContextWithWorkingTracing();
    TestFixture fixture = fixtureWith(artifactsDir, browserContext, page, true);

    fixture.captureFailure(context("someTest"));

    assertThat(expectedTestDirectory(artifactsDir, "someTest").resolve("trace.zip")).exists();
  }

  @Test
  void anAllureAttachmentFailureDoesNotPreventTheManifestEntryFromBeingRecorded(
      @TempDir Path artifactsDir) throws Exception {
    Page page = workingScreenshotPage();
    BrowserContext browserContext = browserContextWithWorkingTracing();
    TestFixture fixture = fixtureWith(artifactsDir, browserContext, page, true);

    try (MockedStatic<Allure> allure = mockStatic(Allure.class)) {
      allure
          .when(
              () ->
                  Allure.addAttachment(
                      anyString(), anyString(), any(InputStream.class), anyString()))
          .thenThrow(new RuntimeException("simulated Allure failure"));

      fixture.captureFailure(context("someTest"));
    }

    List<ArtifactManifestEntry> entries = readManifest(artifactsDir);
    assertThat(entries)
        .extracting(ArtifactManifestEntry::type)
        .containsExactlyInAnyOrder(ArtifactType.SCREENSHOT, ArtifactType.TRACE);
  }

  @Test
  void everyStepFailingStillCompletesWithoutThrowing(@TempDir Path artifactsDir) throws Exception {
    Page page = mock(Page.class);
    when(page.isClosed()).thenReturn(false);
    doThrow(new RuntimeException("simulated screenshot failure"))
        .when(page)
        .screenshot(any(Page.ScreenshotOptions.class));
    BrowserContext browserContext = mock(BrowserContext.class);
    Tracing tracing = mock(Tracing.class);
    when(browserContext.tracing()).thenReturn(tracing);
    doThrow(new RuntimeException("simulated trace stop failure"))
        .when(tracing)
        .stop(any(Tracing.StopOptions.class));
    TestFixture fixture = fixtureWith(artifactsDir, browserContext, page, true);
    ExtensionContext context = context("someTest");

    // The point of this assertion: captureFailure() is called from AutomationExtension's own
    // afterTestExecution, precisely because a real test failure already occurred - it must never
    // itself throw and become a second, different failure that could confuse or replace the
    // original one JUnit already recorded for this test.
    assertThatCode(() -> fixture.captureFailure(context)).doesNotThrowAnyException();
    assertThat(readManifest(artifactsDir)).isEmpty();
  }

  private static Page workingScreenshotPage() {
    Page page = mock(Page.class);
    when(page.isClosed()).thenReturn(false);
    doAnswer(
            invocation -> {
              Page.ScreenshotOptions options = invocation.getArgument(0);
              Files.createDirectories(options.path.getParent());
              Files.writeString(options.path, "fake screenshot bytes");
              return new byte[0];
            })
        .when(page)
        .screenshot(any(Page.ScreenshotOptions.class));
    return page;
  }

  private static BrowserContext browserContextWithWorkingTracing() {
    BrowserContext browserContext = mock(BrowserContext.class);
    Tracing tracing = mock(Tracing.class);
    when(browserContext.tracing()).thenReturn(tracing);
    doAnswer(
            invocation -> {
              Tracing.StopOptions options = invocation.getArgument(0);
              Files.createDirectories(options.path.getParent());
              Files.writeString(options.path, "fake trace bytes");
              return null;
            })
        .when(tracing)
        .stop(any(Tracing.StopOptions.class));
    return browserContext;
  }

  private static TestFixture fixtureWith(
      Path artifactsDir, BrowserContext browserContext, Page page, boolean traceRunning)
      throws Exception {
    TestConfig config =
        new TestConfig(
            "https://automationintesting.online",
            BrowserName.CHROMIUM,
            true,
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            true,
            false,
            artifactsDir,
            "run-1",
            "admin",
            "password",
            "https://automationintesting.online",
            false);
    TestFixture fixture = new TestFixture(config, null);
    setField(fixture, "browserContext", browserContext);
    setField(fixture, "page", page);
    setField(fixture, "traceRunning", traceRunning);
    return fixture;
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = TestFixture.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static ExtensionContext context(String displayName) {
    ExtensionContext context = mock(ExtensionContext.class);
    when(context.getUniqueId()).thenReturn(uniqueIdFor(displayName));
    when(context.getDisplayName()).thenReturn(displayName);
    doReturn(TestFixtureTest.class).when(context).getRequiredTestClass();
    return context;
  }

  private static String uniqueIdFor(String displayName) {
    return "[engine:junit-jupiter]/[method:" + displayName + "]";
  }

  /** Mirrors TestFixture#artifactName exactly, so tests can locate the same directory it uses. */
  private static Path expectedTestDirectory(Path artifactsDir, String displayName) {
    String readable = TestFixtureTest.class.getSimpleName() + "-" + displayName;
    String slug = readable.replaceAll("[^a-zA-Z0-9._-]+", "-").replaceAll("-+", "-");
    String name = slug + "-" + Integer.toHexString(uniqueIdFor(displayName).hashCode());
    return artifactsDir.resolve(name);
  }

  private static List<ArtifactManifestEntry> readManifest(Path artifactsRoot) throws Exception {
    Path manifest = artifactsRoot.resolve("manifest.jsonl");
    if (!Files.exists(manifest)) {
      return List.of();
    }
    return Files.readAllLines(manifest).stream()
        .map(line -> JsonSupport.read(line, ArtifactManifestEntry.class))
        .toList();
  }
}
