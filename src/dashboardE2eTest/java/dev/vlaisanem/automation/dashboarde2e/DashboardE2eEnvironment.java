package dev.vlaisanem.automation.dashboarde2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Starts one real {@code runner-service} + one real {@code runner-dashboard} (served via {@code
 * vite preview} against the production bundle {@code dashboardBuild} builds once in the root {@code
 * build.gradle}, ahead of this whole task - not {@code vite dev}, so this suite's main scenarios
 * exercise the same artifact that would actually ship) + one real Chromium browser, shared across
 * every test class in this suite - started once (on the first test class that reaches {@link
 * #beforeAll}, via the classic JUnit 5 "store a CloseableResource in the root context" trick) and
 * torn down exactly once, when the whole test plan finishes. Each individual test gets its own
 * fresh {@link BrowserContext}/{@link Page} (created in {@link #beforeEach}, closed in {@link
 * #afterTestExecution}) - real isolation between tests, without paying Spring Boot's + Vite's own
 * startup cost per test.
 *
 * <p>The backend-unavailable/recovery scenario deliberately does <b>not</b> use this shared
 * instance (see {@code BackendUnavailableE2eTest}) - it needs to actually kill the backend it
 * asserts against, which this shared instance's other tests all depend on staying up. It serves the
 * same {@code dist/} on its own ports instead of building its own.
 *
 * <p>Every test gets a Playwright trace + a video recording running the whole time, but only a
 * <b>failed</b> test's are actually kept (under {@code build/dashboard-e2e-failures/<test>/},
 * alongside a full-page screenshot) - a passing test's are discarded, and a stale directory from a
 * previous failing run of the same test is cleared in {@link #beforeEach} so a fresh pass can never
 * be mistaken for one. Detected via {@link AfterTestExecutionCallback#afterTestExecution}, the one
 * JUnit 5 callback that exposes the test's own outcome ({@link
 * ExtensionContext#getExecutionException()}) before the context this class owns gets closed.
 *
 * <p>{@link #afterTestExecution} also best-effort cancels whatever run the test's own page ended up
 * on (see {@link #cancelActiveRunIfAny}) - the shared backend is a single-worker executor (see
 * {@code RunService}), so a run left {@code RUNNING} by a failed (or merely abandoned) test would
 * otherwise occupy that one slot and queue every later test's own launched run behind it.
 */
final class DashboardE2eEnvironment
    implements BeforeAllCallback,
        BeforeEachCallback,
        AfterTestExecutionCallback,
        ParameterResolver {

  // 127.0.0.1, not "localhost": Vite's dev server (no --host flag) only binds the IPv4 loopback
  // interface, but "localhost" can resolve to the IPv6 loopback (::1) first depending on the
  // JVM's/OS's own address-ordering - which then fails to connect at all instead of falling back
  // to IPv4, observed live as every health-check attempt timing out for the full poll window.
  // Spring Boot's embedded Tomcat binds all interfaces by default so this never bit the backend
  // the same way, but both are pinned to 127.0.0.1 here for consistency (matching
  // vite.config.ts's own proxy target, which uses the same literal).
  static final String DASHBOARD_BASE_URL = "http://127.0.0.1:5173";
  private static final String BACKEND_HEALTH_URL = "http://127.0.0.1:8080/actuator/health";
  private static final String VIDEO_DIR_KEY = "videoDir";

  // Matches RunDetailsPage.at()'s own URL shape - deliberately loose (any non-empty final path
  // segment), since the same extraction must also harmlessly match NotFoundRunE2eTest's own
  // "no-such-run-id" (the /cancel POST below 404s for that case and is skipped, see
  // cancelActiveRunIfAny) rather than needing every caller to special-case it.
  private static final Pattern RUN_URL_PATTERN = Pattern.compile(".*/runs/([^/?#]+)/?$");
  private static final Set<String> TERMINAL_RUN_STATUSES =
      Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "ERROR");
  private static final Duration RUN_CLEANUP_TIMEOUT = Duration.ofSeconds(30);
  private static final ObjectMapper RUN_CLEANUP_MAPPER = new ObjectMapper();

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(DashboardE2eEnvironment.class);

  @Override
  public void beforeAll(ExtensionContext context) {
    // Not getOrComputeIfAbsent (deprecated in this JUnit Platform version, with no replacement
    // that isn't itself already deprecated) - the dashboardE2eTest task runs sequentially (no
    // parallel test-class execution configured), so a plain get-then-put has no race to guard
    // against.
    ExtensionContext.Store store = context.getRoot().getStore(NAMESPACE);
    if (store.get(SharedResources.class, SharedResources.class) == null) {
      store.put(SharedResources.class, new SharedResources());
    }
  }

  @Override
  public void beforeEach(ExtensionContext context) throws IOException {
    BrowserFailureArtifacts.clearStale(failureDirFor(context));

    Browser browser = sharedResources(context).browser;
    Path videoDir = Files.createTempDirectory("dashboard-e2e-video-");
    BrowserContext browserContext =
        browser.newContext(new Browser.NewContextOptions().setRecordVideoDir(videoDir));
    BrowserFailureArtifacts.startTracing(browserContext);
    Page page = browserContext.newPage();
    context.getStore(NAMESPACE).put(BrowserContext.class, browserContext);
    context.getStore(NAMESPACE).put(Page.class, page);
    context.getStore(NAMESPACE).put(VIDEO_DIR_KEY, videoDir);
  }

  @Override
  public void afterTestExecution(ExtensionContext context) {
    ExtensionContext.Store store = context.getStore(NAMESPACE);
    BrowserContext browserContext = store.remove(BrowserContext.class, BrowserContext.class);
    Page page = store.get(Page.class, Page.class);
    Path videoDir = store.remove(VIDEO_DIR_KEY, Path.class);
    boolean failed = context.getExecutionException().isPresent();
    Path failureDir = failureDirFor(context);

    // Screenshot and trace FIRST, before anything below that could itself change what is on
    // screen - cancelActiveRunIfAny flips a still-RUNNING run to CANCELLED, and capturing evidence
    // after that would show a state the test never actually failed in, hiding the real one.
    BrowserFailureArtifacts.captureBeforeClose(failureDir, failed, page, browserContext);

    // Independent of pass/fail: a run this test launched must never be left occupying the shared
    // backend's single-worker slot, whether or not artifact capture itself went smoothly.
    BrowserFailureArtifacts.safely(() -> cancelActiveRunIfAny(page));

    // Playwright only finalizes a context's recorded video once the context itself closes - the
    // final path is not available to read beforehand. Run unconditionally, even if a step above
    // threw, so the context (and the browser resource it holds) is never leaked.
    BrowserFailureArtifacts.safely(browserContext::close);
    BrowserFailureArtifacts.saveVideoIfFailed(failureDir, failed, page);
    BrowserFailureArtifacts.safely(() -> BrowserFailureArtifacts.deleteRecursively(videoDir));
  }

  private static Path failureDirFor(ExtensionContext context) {
    return BrowserFailureArtifacts.directoryFor(
        context.getRequiredTestClass(), context.getRequiredTestMethod().getName());
  }

  /**
   * Best-effort (called from within {@code safely()} - see the call site): a run left {@code
   * RUNNING}/{@code QUEUED}/{@code STARTING} by a failed (or merely slow) test would otherwise keep
   * occupying the runner-service's single-worker execution slot (a fixed {@code 1,1} {@code
   * ThreadPoolExecutor} - see {@code RunService}), queuing every later test's own launched run
   * behind it and cascading timeouts across the rest of the suite. Applies to every test, pass or
   * fail: a passing test's own already-terminal run makes the {@code /cancel} call a harmless no-op
   * (see {@code RunService#cancel} - {@code if (current.status().isTerminal()) return current;}).
   *
   * <p>Only a {@code 404} (a test whose final page was never a real run at all, e.g. {@code
   * NotFoundRunE2eTest}) is treated as "nothing to clean up". Every other failure mode - a thrown
   * network exception, a non-{@code 404} non-2xx status, a malformed response body, the
   * terminal-status poll below timing out, or the poll's wait between attempts being interrupted -
   * is deliberately allowed to propagate as an exception: {@code safely()} logs it, so a cleanup
   * that silently failed while a run stayed active is never mistaken for one that actually worked.
   */
  private void cancelActiveRunIfAny(Page page) {
    String url;
    try {
      url = page.url();
    } catch (RuntimeException e) {
      return;
    }
    Matcher matcher = RUN_URL_PATTERN.matcher(url);
    if (!matcher.matches()) {
      return;
    }
    String runUrl = DASHBOARD_BASE_URL + "/api/v1/runs/" + matcher.group(1);

    APIResponse cancelResponse = page.request().post(runUrl + "/cancel");
    if (cancelResponse.status() == 404) {
      return;
    }
    if (!cancelResponse.ok()) {
      throw new IllegalStateException(
          "cancel request for " + runUrl + " returned HTTP " + cancelResponse.status());
    }

    Instant deadline = Instant.now().plus(RUN_CLEANUP_TIMEOUT);
    while (Instant.now().isBefore(deadline)) {
      if (isTerminal(page, runUrl)) {
        return;
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "interrupted while waiting for run at " + runUrl + " to reach a terminal status", e);
      }
    }
    throw new IllegalStateException(
        "run at "
            + runUrl
            + " did not reach a terminal status within "
            + RUN_CLEANUP_TIMEOUT
            + " after being cancelled");
  }

  private boolean isTerminal(Page page, String runUrl) {
    APIResponse response = page.request().get(runUrl);
    if (!response.ok()) {
      throw new IllegalStateException(
          "status check for " + runUrl + " returned HTTP " + response.status());
    }
    JsonNode body;
    try {
      body = RUN_CLEANUP_MAPPER.readTree(response.text());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return TERMINAL_RUN_STATUSES.contains(body.path("status").asText(""));
  }

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext) {
    Class<?> type = parameterContext.getParameter().getType();
    return type == Page.class || type == BrowserContext.class;
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    Class<?> type = parameterContext.getParameter().getType();
    ExtensionContext.Store store = extensionContext.getStore(NAMESPACE);
    if (type == Page.class) {
      return store.get(Page.class, Page.class);
    }
    return store.get(BrowserContext.class, BrowserContext.class);
  }

  private SharedResources sharedResources(ExtensionContext context) {
    return context.getRoot().getStore(NAMESPACE).get(SharedResources.class, SharedResources.class);
  }

  /** Started once for the whole JVM run; closed when the JUnit root context closes. */
  private static final class SharedResources implements AutoCloseable {
    final DashboardProcess backend;
    final DashboardProcess dashboard;
    final Playwright playwright;
    final Browser browser;

    SharedResources() {
      // Tracks whatever has already been started, in start order, so a failure partway through
      // (e.g. the dashboard's health check timing out after the backend already started fine)
      // doesn't leak that already-started process for the rest of the JVM's life - nothing else
      // ever gets a reference to it to close later, since the fields below are only assigned once
      // every step has succeeded.
      List<AutoCloseable> startedSoFar = new ArrayList<>();
      try {
        Path repoRoot = Path.of(System.getProperty("dashboardE2e.repoRoot"));
        Path runnerServiceJar = Path.of(System.getProperty("dashboardE2e.runnerServiceJar"));
        Path dashboardDir = Path.of(System.getProperty("dashboardE2e.dashboardDir"));

        DashboardProcess startedBackend =
            DashboardProcess.start(
                "backend",
                List.of("java", "-jar", runnerServiceJar.toString()),
                repoRoot,
                Map.of(),
                BACKEND_HEALTH_URL,
                Duration.ofMinutes(1));
        startedSoFar.add(startedBackend::stop);

        // `preview`, not `dev`: this is what proves the actual production bundle `dashboardBuild`
        // built once (a Gradle task dependency of dashboardE2eTest, see root build.gradle) works
        // end to end, rather than only ever exercising Vite's dev-mode transform pipeline.
        DashboardProcess startedDashboard =
            DashboardProcess.start(
                "dashboard",
                npmCommand(
                    "run",
                    "preview",
                    "--",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    "5173",
                    "--strictPort"),
                dashboardDir,
                Map.of(),
                DASHBOARD_BASE_URL + "/",
                Duration.ofMinutes(1));
        startedSoFar.add(startedDashboard::stop);

        Playwright startedPlaywright = Playwright.create();
        startedSoFar.add(startedPlaywright);

        Browser startedBrowser =
            startedPlaywright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        startedSoFar.add(startedBrowser);

        this.backend = startedBackend;
        this.dashboard = startedDashboard;
        this.playwright = startedPlaywright;
        this.browser = startedBrowser;
      } catch (Exception e) {
        for (int i = startedSoFar.size() - 1; i >= 0; i--) {
          try {
            startedSoFar.get(i).close();
          } catch (Exception closeFailure) {
            e.addSuppressed(closeFailure);
          }
        }
        throw new IllegalStateException("Failed to start the shared dashboard E2E environment", e);
      }
    }

    @Override
    public void close() {
      // Each step independent, not a plain sequential chain: an exception from an earlier step
      // (e.g. browser.close() already having been called, or already crashed) must not skip the
      // later ones - especially dashboard.stop()/backend.stop(), which is what actually frees the
      // ports for the next run.
      List<Runnable> steps =
          List.of(browser::close, playwright::close, dashboard::stop, backend::stop);
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
  }

  static List<String> npmCommand(String... args) {
    boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    List<String> command = new ArrayList<>();
    if (windows) {
      // ProcessBuilder's array form calls CreateProcess directly with the first element as the
      // executable - npm.cmd is a shell script, not a native PE executable, so Windows refuses to
      // launch it that way (error=2) without cmd.exe's own interpreter in front of it.
      command.add("cmd.exe");
      command.add("/c");
      command.add("npm.cmd");
    } else {
      command.add("npm");
    }
    command.addAll(List.of(args));
    return command;
  }
}
