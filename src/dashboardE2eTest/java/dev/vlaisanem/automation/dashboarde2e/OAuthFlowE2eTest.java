package dev.vlaisanem.automation.dashboarde2e;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import dev.vlaisanem.automation.dashboarde2e.pages.RunDetailsPage;
import dev.vlaisanem.automation.dashboarde2e.pages.RunsListPage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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
 * D3.4 real-browser proof of the whole GitHub OAuth2 Login round trip - login, launch, cancel,
 * logout - that D3.2 deliberately never automated against the real GitHub (see {@code
 * docs/RELEASE_EVIDENCE.md}'s D3.2 section: a real click-through was done manually instead, to
 * avoid real-account/network flakiness in CI). This class closes that gap without reintroducing
 * that risk: {@link #gitHubStub} stands in for GitHub's own {@code authorize}/{@code
 * access_token}/{@code user} endpoints, and the real {@code runner-service} is launched with its
 * normal Spring Boot OAuth2 client binding - {@code
 * spring.security.oauth2.client.provider.github.*} overrides only the three endpoint URIs (the
 * standard, supported way to redirect an existing {@code CommonOAuth2Provider.GITHUB}-derived
 * registration at a different provider - see {@code OAuth2ClientPropertiesRegistrationAdapter}),
 * never a fake in-test {@code ClientRegistrationRepository} - so this exercises the real {@code
 * RunnerSecurityEnvironmentPostProcessor} bridging, the real {@code ClientRegistrationRepository},
 * the real authorization redirect/state, the real callback, the real token exchange, the real
 * {@code GithubOAuth2UserService}, real session creation, real CSRF, and the real frontend auth
 * state - everything a genuine GitHub round trip would exercise except GitHub itself.
 *
 * <p>Deliberately its own fully isolated backend+dashboard+stub, on its own ports, never the shared
 * {@link DashboardE2eEnvironment} instance every other test in this suite uses (which runs the
 * permissive, no-OAuth2 chain) - mirrors {@link BackendUnavailableE2eTest}'s own isolation pattern.
 * The backend is restarted per test (see {@link #startBackend}) so each test gets a fresh session
 * store/rate-limiter state and its own {@code RUNNER_SECURITY_ADMIN_GITHUB_ID}, without needing to
 * reconfigure the one shared stub between tests.
 */
@ExtendWith(OAuthFlowE2eTest.FailureFlag.class)
class OAuthFlowE2eTest {

  private static final int BACKEND_PORT = 8082;
  private static final int DASHBOARD_PORT = 5175;
  private static final String DASHBOARD_BASE_URL = "http://127.0.0.1:" + DASHBOARD_PORT;
  private static final String BACKEND_HEALTH_URL =
      "http://127.0.0.1:" + BACKEND_PORT + "/actuator/health";

  // The one numeric id the stub's /user response ever returns - which tests get an admin session
  // is controlled entirely by which RUNNER_SECURITY_ADMIN_GITHUB_ID each test's own backend
  // instance is started with (see startBackend), never by reconfiguring the stub itself.
  private static final String STUB_GITHUB_USER_ID = "999";
  private static final String NON_MATCHING_ADMIN_ID = "42";
  private static final String FAKE_AUTHORIZATION_CODE = "fake-authorization-code";
  private static final String FAKE_ACCESS_TOKEN = "fake-access-token";

  private static Path repoRoot;
  private static Path runnerServiceJar;
  private static DashboardProcess dashboardPreview;
  private static Playwright playwright;
  private static Browser browser;
  private static WireMockServer gitHubStub;

  private DashboardProcess backend;
  private Page page;
  private Path videoDir;
  private Path failureDir;
  private boolean testFailed;

  @BeforeAll
  static void startSharedFixtures() throws IOException {
    repoRoot = Path.of(System.getProperty("dashboardE2e.repoRoot"));
    runnerServiceJar = Path.of(System.getProperty("dashboardE2e.runnerServiceJar"));
    Path dashboardDir = Path.of(System.getProperty("dashboardE2e.dashboardDir"));

    List<AutoCloseable> startedSoFar = new ArrayList<>();
    try {
      WireMockServer startedStub =
          new WireMockServer(WireMockConfiguration.options().dynamicPort().globalTemplating(true));
      startedStub.start();
      startedSoFar.add(startedStub::stop);
      stubGitHub(startedStub);

      DashboardProcess startedDashboard =
          DashboardProcess.start(
              "dashboard-preview-oauth",
              DashboardE2eEnvironment.npmCommand(
                  "run",
                  "preview",
                  "--",
                  "--host",
                  "127.0.0.1",
                  "--port",
                  String.valueOf(DASHBOARD_PORT),
                  "--strictPort"),
              dashboardDir,
              Map.of("RUNNER_API_TARGET", "http://127.0.0.1:" + BACKEND_PORT),
              DASHBOARD_BASE_URL + "/",
              Duration.ofMinutes(1));
      startedSoFar.add(startedDashboard::stop);

      Playwright startedPlaywright = Playwright.create();
      startedSoFar.add(startedPlaywright);

      Browser startedBrowser =
          startedPlaywright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
      startedSoFar.add(startedBrowser);

      gitHubStub = startedStub;
      dashboardPreview = startedDashboard;
      playwright = startedPlaywright;
      browser = startedBrowser;
    } catch (IOException | RuntimeException e) {
      gitHubStub = null;
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

  /**
   * The authorize stub echoes back whatever {@code redirect_uri}/{@code state} Spring Security's
   * own {@code OAuth2AuthorizationRequestRedirectFilter} sent - exactly what a real GitHub
   * authorize endpoint does, and the only way the real {@code state} CSRF-style check on callback
   * can ever pass. The token/user-info stubs are otherwise static: only one fake identity ({@link
   * #STUB_GITHUB_USER_ID}) is ever configured, and it is the backend's own {@code
   * RUNNER_SECURITY_ADMIN_GITHUB_ID} (varied per test, see {@link #startBackend}) that decides
   * whether that identity is treated as the admin or rejected.
   */
  private static void stubGitHub(WireMockServer stub) {
    stub.stubFor(
        get(urlPathEqualTo("/login/oauth/authorize"))
            .willReturn(
                aResponse()
                    .withStatus(302)
                    .withHeader(
                        "Location",
                        "{{request.query.redirect_uri}}?code="
                            + FAKE_AUTHORIZATION_CODE
                            + "&state={{request.query.state}}")));

    stub.stubFor(
        post(urlPathEqualTo("/login/oauth/access_token"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"access_token\":\""
                            + FAKE_ACCESS_TOKEN
                            + "\",\"token_type\":\"Bearer\",\"scope\":\"read:user\"}")));

    stub.stubFor(
        get(urlPathEqualTo("/user"))
            .withHeader("Authorization", equalTo("Bearer " + FAKE_ACCESS_TOKEN))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"id\":"
                            + STUB_GITHUB_USER_ID
                            + ",\"login\":\"octocat\",\"avatar_url\":\"https://avatars"
                            + ".githubusercontent.com/u/"
                            + STUB_GITHUB_USER_ID
                            + "\"}")));
  }

  @AfterAll
  static void stopSharedFixtures() {
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
    if (gitHubStub != null) {
      steps.add(gitHubStub::stop);
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
  void createPage(TestInfo testInfo) {
    failureDir =
        BrowserFailureArtifacts.directoryFor(
            getClass(), testInfo.getTestMethod().orElseThrow().getName());
    BrowserFailureArtifacts.clearStale(failureDir);
    // The stub itself (its mappings) is shared for the whole class, but its request journal is
    // not test-scoped by default - a review finding: without this, a later test's own
    // gitHubStub.verify(...) call could be satisfied by a request an earlier test made, silently
    // proving nothing about what *this* test's own login attempt actually did.
    gitHubStub.resetRequests();
    videoDir = uncheckedTempDir();
    page = browser.newPage(new Browser.NewPageOptions().setRecordVideoDir(videoDir));
    BrowserFailureArtifacts.startTracing(page.context());
  }

  /**
   * Each step runs independently of whether an earlier one threw - a review finding against an
   * earlier version that ran {@code page.context().close()} and {@code backend.stop()} unprotected
   * inside a single try/finally, where either one throwing skipped a later step entirely (a failed
   * {@code context.close()} meant the video was never saved; a failed {@code backend.stop()} meant
   * the temp video directory was never deleted). Mirrors {@code
   * DashboardE2eEnvironment.SharedResources#close}/{@code
   * BackendUnavailableE2eTest#stopIsolatedDashboardPreview}'s own collect-then-rethrow pattern -
   * the original test's own failure (if any) is a separate, already-recorded JUnit outcome; this
   * only decides whether a *cleanup* failure is also surfaced, without ever hiding one behind
   * another.
   *
   * <p>{@code captureBeforeClose}/{@code saveVideoIfFailed} are wrapped here too, not just {@code
   * context.close()}/{@code backend.stop()} - a review finding: {@code
   * BrowserFailureArtifacts#safely} only guards the individual screenshot/tracing/{@code
   * Files.move} calls *inside* those two methods, but {@code captureBeforeClose}'s own {@code
   * page.context()} argument is evaluated before the method is even entered, and {@code
   * saveVideoIfFailed}'s first statement ({@code page.video()}) runs before its own {@code
   * safely()} block - either one throwing (a real possibility if the Playwright transport itself
   * has already failed) would otherwise skip every later step, including {@code backend.stop()}.
   */
  @AfterEach
  void cleanup() {
    RuntimeException failure = null;
    if (page != null) {
      try {
        BrowserFailureArtifacts.captureBeforeClose(failureDir, testFailed, page, page.context());
      } catch (RuntimeException e) {
        failure = e;
      }
      try {
        page.context().close();
      } catch (RuntimeException e) {
        failure = chain(failure, e);
      }
      try {
        BrowserFailureArtifacts.saveVideoIfFailed(failureDir, testFailed, page);
      } catch (RuntimeException e) {
        failure = chain(failure, e);
      }
    }
    if (backend != null) {
      try {
        backend.stop();
      } catch (RuntimeException e) {
        failure = chain(failure, e);
      }
    }
    // Runs regardless of whether backend.stop() above threw - already internally safe.
    BrowserFailureArtifacts.safely(() -> BrowserFailureArtifacts.deleteRecursively(videoDir));
    if (failure != null) {
      throw failure;
    }
  }

  private static RuntimeException chain(RuntimeException existing, RuntimeException next) {
    if (existing == null) {
      return next;
    }
    existing.addSuppressed(next);
    return existing;
  }

  static final class FailureFlag implements AfterTestExecutionCallback {
    @Override
    public void afterTestExecution(ExtensionContext context) {
      ((OAuthFlowE2eTest) context.getRequiredTestInstance()).testFailed =
          context.getExecutionException().isPresent();
    }
  }

  @Test
  @Timeout(120)
  void loginLaunchCancelLogoutRoundTripsThroughTheRealOAuth2Flow() throws IOException {
    backend = startBackend(STUB_GITHUB_USER_ID);

    RunsListPage runsList = RunsListPage.open(page, DASHBOARD_BASE_URL);
    Locator runButton =
        page.locator("form")
            .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Run"));

    // Anonymous: the login concept exists (authenticationRequired), but nobody is logged in yet -
    // the launch control is present but disabled, never silently hidden or silently enabled.
    assertThat(
            page.getByRole(
                AriaRole.LINK, new Page.GetByRoleOptions().setName("Log in with GitHub")))
        .isVisible();
    assertThat(runButton).isDisabled();

    // Not page.waitForURL(DASHBOARD_BASE_URL + "/runs") - a review finding: the page is already
    // sitting on exactly that URL before this click (RunsListPage.open navigated there), so that
    // wait would resolve the instant it's called, before the real redirect chain (dashboard ->
    // backend authorize redirect -> stub GitHub authorize -> stub redirect back to the real
    // callback -> real token exchange -> real user-info call -> real session -> back to the
    // dashboard) has necessarily gone anywhere at all - only appearing to prove the round trip by
    // the coincidental timing of everything being local and fast. Waiting for the real callback
    // response (registered before the click that triggers it, the same two-arg pattern used
    // below for logout) is what actually proves it landed.
    page.waitForResponse(
        response -> response.url().contains("/api/v1/auth/oauth2/callback/github"),
        () ->
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Log in with GitHub"))
                .click());
    page.waitForURL(DASHBOARD_BASE_URL + "/runs");

    assertMe(true, true, "octocat");

    // CSRF: launching a run is a state-changing POST behind the same CSRF protection every other
    // authenticated mutation uses - succeeding here proves the token was actually primed and sent,
    // not merely that authentication itself succeeded.
    RunDetailsPage details = runsList.selectSuite("FIXTURE").launchRun();
    details.waitForStatus("RUNNING", Duration.ofSeconds(30));
    assertThat(details.cancelButton()).isEnabled();
    details.cancelButton().click();
    details.waitForStatus("CANCELLED", Duration.ofSeconds(30));

    // Not page.waitForURL(...) - a review finding: logout is a plain fetch() with no navigation
    // (see AuthControls.tsx), and the current /runs/{runId} URL already matches
    // DASHBOARD_BASE_URL + "/**" before the click, so that wait would resolve immediately without
    // ever proving the logout request itself completed. Wait for the real 204 response instead,
    // then for the UI to actually reflect the now-anonymous state.
    page.waitForResponse(
        response -> response.url().endsWith("/api/v1/auth/logout") && response.status() == 204,
        () ->
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Log out"))
                .click());
    assertThat(
            page.getByRole(
                AriaRole.LINK, new Page.GetByRoleOptions().setName("Log in with GitHub")))
        .isVisible();

    assertMe(true, false, null);
    // Logout happens from the run-details page (no launch form there at all) - back to /runs to
    // re-check the control's own state, proving the old session can no longer launch a run either.
    RunsListPage.open(page, DASHBOARD_BASE_URL);
    assertThat(runButton).isDisabled();

    gitHubStub.verify(
        postRequestedFor(urlPathEqualTo("/login/oauth/access_token"))
            .withRequestBody(containing("code=" + FAKE_AUTHORIZATION_CODE)));
    gitHubStub.verify(
        getRequestedFor(urlPathEqualTo("/user"))
            .withHeader("Authorization", equalTo("Bearer " + FAKE_ACCESS_TOKEN)));
  }

  /**
   * A GitHub identity that authenticates successfully but does not match this instance's own {@code
   * RUNNER_SECURITY_ADMIN_GITHUB_ID} must never receive an admin session - {@link
   * GithubOAuth2UserService} throws before any {@code SecurityContext} is ever established (see its
   * own Javadoc), so the login fails outright rather than landing in some intermediate
   * authenticated-but-not-admin state.
   */
  @Test
  @Timeout(60)
  void aNonAllowlistedGithubIdentityIsRejectedWithNoAdminSession() throws IOException {
    backend = startBackend(NON_MATCHING_ADMIN_ID);

    RunsListPage.open(page, DASHBOARD_BASE_URL);
    // Not just "ended up back on some dashboard URL" - a review finding: the dashboard's own base
    // URL matches that same predicate before the click too, so a login attempt that failed to
    // even reach GitHub (a broken redirect, a stub never called at all) would satisfy a plain
    // waitForURL just as trivially as a real, completed, correctly-rejected round trip would.
    // Waiting for the real callback response (the two-arg form, registering the listener BEFORE
    // the click that triggers it - a single-arg wait issued after the click would race a
    // same-machine round trip fast enough to already have completed) is what actually proves it.
    page.waitForResponse(
        response -> response.url().contains("/api/v1/auth/oauth2/callback/github"),
        () ->
            page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Log in with GitHub"))
                .click());

    assertMe(true, false, null);

    // The real proof this is a rejection, not a round trip that silently never happened: both the
    // token exchange and the user-info call must show up in the stub's own request journal for
    // *this* test (reset fresh in @BeforeEach - see its own comment).
    gitHubStub.verify(
        postRequestedFor(urlPathEqualTo("/login/oauth/access_token"))
            .withRequestBody(containing("code=" + FAKE_AUTHORIZATION_CODE)));
    gitHubStub.verify(
        getRequestedFor(urlPathEqualTo("/user"))
            .withHeader("Authorization", equalTo("Bearer " + FAKE_ACCESS_TOKEN)));
  }

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private void assertMe(boolean authenticationRequired, boolean authenticated, String login) {
    APIResponse response = page.request().get(DASHBOARD_BASE_URL + "/api/v1/auth/me");
    assertThat(response.status()).isEqualTo(200);
    JsonNode body;
    try {
      body = OBJECT_MAPPER.readTree(response.text());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    assertThat(body.path("authenticationRequired").asBoolean()).isEqualTo(authenticationRequired);
    assertThat(body.path("authenticated").asBoolean()).isEqualTo(authenticated);
    if (login != null) {
      assertThat(body.path("login").asText()).isEqualTo(login);
    }
  }

  private static DashboardProcess startBackend(String adminGithubId) throws IOException {
    Map<String, String> env = new HashMap<>(DashboardE2eDatabase.connectionEnv());
    env.put("RUNNER_SECURITY_GITHUB_CLIENT_ID", "test-client-id");
    env.put("RUNNER_SECURITY_GITHUB_CLIENT_SECRET", "test-client-secret");
    env.put("RUNNER_SECURITY_ADMIN_GITHUB_ID", adminGithubId);

    return DashboardProcess.start(
        "backend-oauth-e2e",
        List.of(
            "java",
            "-jar",
            runnerServiceJar.toString(),
            "--server.port=" + BACKEND_PORT,
            "--spring.security.oauth2.client.provider.github.authorization-uri="
                + gitHubStub.baseUrl()
                + "/login/oauth/authorize",
            "--spring.security.oauth2.client.provider.github.token-uri="
                + gitHubStub.baseUrl()
                + "/login/oauth/access_token",
            "--spring.security.oauth2.client.provider.github.user-info-uri="
                + gitHubStub.baseUrl()
                + "/user",
            "--spring.security.oauth2.client.provider.github.user-name-attribute=id"),
        repoRoot,
        env,
        BACKEND_HEALTH_URL,
        Duration.ofMinutes(1));
  }

  private static Path uncheckedTempDir() {
    try {
      return Files.createTempDirectory("dashboard-e2e-video-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
