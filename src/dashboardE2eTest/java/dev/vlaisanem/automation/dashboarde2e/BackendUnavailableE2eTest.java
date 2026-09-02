package dev.vlaisanem.automation.dashboarde2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.LocatorAssertions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Deliberately does <b>not</b> use {@link DashboardE2eEnvironment} - this scenario needs to
 * actually kill the backend it asserts against, and every other test in this suite shares that
 * extension's one backend+dashboard instance. Instead, this class manages its own fully separate
 * pair on different ports (backend {@value #ISOLATED_BACKEND_PORT}, dashboard {@value
 * #ISOLATED_DASHBOARD_PORT}), so killing "the backend" here can never affect any other test.
 *
 * <p>Uses {@code vite preview} (the production build), not {@code vite dev} - the shared
 * environment's dashboard is fixed to the default backend port via {@code vite.config.ts}'s own
 * proxy target, so an isolated instance needs a way to point at a <i>different</i> backend port
 * instead; {@code vite.config.ts} was changed to read that target from a plain {@code
 * RUNNER_API_TARGET} environment variable specifically for this. {@code vite preview} reuses {@code
 * server.proxy} for its own proxy config when {@code preview.proxy} is not set separately, so no
 * extra config was needed there - only {@code --strictPort} to guarantee it actually binds {@link
 * #ISOLATED_DASHBOARD_PORT} rather than silently picking another one.
 *
 * <p>Still gets the same screenshot/trace/video-on-failure evidence as every other test in this
 * suite (see {@link BrowserFailureArtifacts}), just wired up directly in {@link
 * #startIsolatedBackend} / {@link #cleanup} instead of through {@code DashboardE2eEnvironment}'s
 * extension - this class's own {@code @BeforeEach}/{@code @AfterEach} already own the page's
 * lifecycle, and {@link FailureFlag} (a minimal {@link AfterTestExecutionCallback}) is the only
 * piece needed on top to know whether the test failed, since {@code @AfterEach} methods have no
 * direct way to ask that.
 */
@ExtendWith(BackendUnavailableE2eTest.FailureFlag.class)
class BackendUnavailableE2eTest {

  private static final int ISOLATED_BACKEND_PORT = 8081;
  private static final int ISOLATED_DASHBOARD_PORT = 5174;
  private static final String ISOLATED_DASHBOARD_BASE_URL =
      "http://127.0.0.1:" + ISOLATED_DASHBOARD_PORT;
  private static final String ISOLATED_BACKEND_HEALTH_URL =
      "http://127.0.0.1:" + ISOLATED_BACKEND_PORT + "/actuator/health";

  private static Path repoRoot;
  private static Path runnerServiceJar;
  private static DashboardProcess dashboardPreview;
  private static Playwright playwright;
  private static Browser browser;

  private DashboardProcess isolatedBackend;
  private Page page;
  private Path videoDir;
  private Path failureDir;
  private boolean testFailed;

  @BeforeAll
  static void startIsolatedDashboardPreview() throws IOException {
    repoRoot = Path.of(System.getProperty("dashboardE2e.repoRoot"));
    runnerServiceJar = Path.of(System.getProperty("dashboardE2e.runnerServiceJar"));
    Path dashboardDir = Path.of(System.getProperty("dashboardE2e.dashboardDir"));

    // Tracks whatever has already started, in start order, so a failure partway through (e.g. the
    // preview process starting fine but Playwright.create() then failing) doesn't leak it - see
    // DashboardE2eEnvironment.SharedResources' own constructor for the same pattern.
    List<AutoCloseable> startedSoFar = new ArrayList<>();
    try {
      // The production bundle itself (dashboardDir/dist) is built exactly once by the root
      // build.gradle's own `dashboardBuild` task, a dependency of dashboardE2eTest that runs
      // before this JVM even starts - shared with DashboardE2eEnvironment's own `vite preview`
      // instance, so it is already present here.
      dashboardPreview =
          DashboardProcess.start(
              "dashboard-preview-isolated",
              DashboardE2eEnvironment.npmCommand(
                  "run",
                  "preview",
                  "--",
                  "--host",
                  "127.0.0.1",
                  "--port",
                  String.valueOf(ISOLATED_DASHBOARD_PORT),
                  "--strictPort"),
              dashboardDir,
              Map.of("RUNNER_API_TARGET", "http://127.0.0.1:" + ISOLATED_BACKEND_PORT),
              ISOLATED_DASHBOARD_BASE_URL + "/",
              Duration.ofMinutes(1));
      startedSoFar.add(dashboardPreview::stop);

      playwright = Playwright.create();
      startedSoFar.add(playwright);

      browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
      startedSoFar.add(browser);
    } catch (IOException | RuntimeException e) {
      // Null out the static fields so a subsequent @AfterAll (JUnit still runs it even though
      // @BeforeAll failed) sees only what is actually still open, rather than a stale reference to
      // something this rollback already closed.
      dashboardPreview = null;
      playwright = null;
      browser = null;
      for (int i = startedSoFar.size() - 1; i >= 0; i--) {
        try {
          startedSoFar.get(i).close();
        } catch (Exception closeFailure) {
          e.addSuppressed(closeFailure);
        }
      }
      throw e;
    }
  }

  @AfterAll
  static void stopIsolatedDashboardPreview() {
    // Each step independent, not a plain sequential chain, and null-checked: @BeforeAll may have
    // rolled back and nulled out some or all of these fields already (see its own catch block) if
    // it failed partway through, and an exception from one live step here must not skip the
    // others - see DashboardE2eEnvironment.SharedResources#close for the same pattern.
    List<Runnable> steps = new ArrayList<>();
    if (browser != null) {
      steps.add(browser::close);
    }
    if (playwright != null) {
      steps.add(playwright::close);
    }
    if (dashboardPreview != null) {
      steps.add(dashboardPreview::stop);
    }

    RuntimeException failure = null;
    for (Runnable step : steps) {
      try {
        step.run();
      } catch (RuntimeException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  @BeforeEach
  void startIsolatedBackend(TestInfo testInfo) throws IOException {
    failureDir =
        BrowserFailureArtifacts.directoryFor(
            getClass(), testInfo.getTestMethod().orElseThrow().getName());
    BrowserFailureArtifacts.clearStale(failureDir);
    isolatedBackend = startBackendOnIsolatedPort();
    videoDir = Files.createTempDirectory("dashboard-e2e-video-");
    page = browser.newPage(new Browser.NewPageOptions().setRecordVideoDir(videoDir));
    BrowserFailureArtifacts.startTracing(page.context());
  }

  @AfterEach
  void cleanup() {
    // try/finally, and null-checked: if @BeforeEach failed after starting isolatedBackend but
    // before page was assigned (or vice versa), one must not be skipped because closing the other
    // threw or because it was never set in the first place.
    try {
      if (page != null) {
        // Screenshot and trace FIRST, before closing anything below that would change what's on
        // screen or finalize the video - see BrowserFailureArtifacts for why the ordering matters.
        BrowserFailureArtifacts.captureBeforeClose(failureDir, testFailed, page, page.context());
        // Closes the context (not just the page) so the recorded video is actually finalized -
        // same as DashboardE2eEnvironment's own browserContext.close(), just reached via the page
        // since this class never holds a separate BrowserContext reference.
        page.context().close();
        BrowserFailureArtifacts.saveVideoIfFailed(failureDir, testFailed, page);
      }
    } finally {
      if (isolatedBackend != null) {
        isolatedBackend.stop();
      }
      BrowserFailureArtifacts.safely(() -> BrowserFailureArtifacts.deleteRecursively(videoDir));
    }
  }

  /**
   * The only piece of {@code DashboardE2eEnvironment}'s extension this class still needs: exposes
   * whether the test failed to {@link #cleanup}, which - as a plain {@code @AfterEach} method - has
   * no direct way to ask that itself. Runs after the test but before {@code @AfterEach}, on the
   * same test instance, so setting a field here is visible there.
   */
  static final class FailureFlag implements AfterTestExecutionCallback {
    @Override
    public void afterTestExecution(ExtensionContext context) {
      ((BackendUnavailableE2eTest) context.getRequiredTestInstance()).testFailed =
          context.getExecutionException().isPresent();
    }
  }

  @Test
  @Timeout(90)
  void healthIndicatorReflectsAnOutageAndRecoversWithoutReloading() throws IOException {
    page.navigate(ISOLATED_DASHBOARD_BASE_URL + "/runs");
    assertVisibleWithin(page.getByText("Runner service: UP"), Duration.ofSeconds(15));

    isolatedBackend.stop();

    assertVisibleWithin(
        page.getByText("Runner service unavailable", new Page.GetByTextOptions().setExact(false)),
        Duration.ofSeconds(15));

    // Same port, a genuinely new process - not the one just stopped.
    isolatedBackend = startBackendOnIsolatedPort();

    // No page.reload() here - AppShell's own health polling (see AppShell.tsx) must pick this up
    // entirely on its own, the same behavior AppShell.test.tsx already covers at the component
    // level; this is the same recovery proven live, end to end.
    assertVisibleWithin(page.getByText("Runner service: UP"), Duration.ofSeconds(15));
  }

  private static DashboardProcess startBackendOnIsolatedPort() throws IOException {
    return DashboardProcess.start(
        "backend-isolated",
        List.of(
            "java", "-jar", runnerServiceJar.toString(), "--server.port=" + ISOLATED_BACKEND_PORT),
        repoRoot,
        Map.of(),
        ISOLATED_BACKEND_HEALTH_URL,
        Duration.ofMinutes(1));
  }

  private static void assertVisibleWithin(
      com.microsoft.playwright.Locator locator, Duration timeout) {
    assertThat(locator)
        .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(timeout.toMillis()));
  }
}
