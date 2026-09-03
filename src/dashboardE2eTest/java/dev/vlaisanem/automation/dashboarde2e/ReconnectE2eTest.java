package dev.vlaisanem.automation.dashboarde2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import dev.vlaisanem.automation.dashboarde2e.pages.RunDetailsPage;
import dev.vlaisanem.automation.dashboarde2e.pages.RunsListPage;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A browser-level SSE transport/recovery integration test (see {@code GapReplayE2eTest}'s own doc
 * comment for why this category, not a full backend E2E, is the honest description) - proving the
 * <i>native</i> {@code EventSource} reconnect path, which is a genuinely different code path from
 * {@code GapReplayE2eTest}'s deliberate close-and-reopen-fresh one: this response simply ends early
 * with no gap and no {@code RUN_FINISHED} (a legitimate mid-run drop, e.g. a proxy hiccup) - per
 * the WHATWG SSE spec (see {@code docs/SSE_CONTRACT_V1.md}'s own note on this), the browser's own
 * {@code EventSource} treats <b>any</b> response completion as reason to auto-reconnect unless the
 * client called {@code close()} itself. The two code paths are told apart by which banner text
 * appears - {@code "Connection lost — reconnecting…"} (native retry, `hasBeenOpen` tracked) here,
 * versus {@code GapReplayE2eTest}'s own {@code "Live stream fell out of sync..."} (the dashboard's
 * own deliberate gap-triggered logic) - not by a {@code Last-Event-ID} header check: that header
 * was empirically observed to never be sent on the reconnect against a {@code route.fulfill()}
 * synthetic response in this Playwright/Chromium combination (verified live - printing the full
 * header set showed no such header at all on either request), most likely because a fully-buffered
 * synthetic body isn't recognized by Chromium's own SSE reader the same way a genuinely streamed
 * response is. That is a limitation of this test double technique, not something this test can
 * responsibly assert on either way.
 *
 * <p>Attempting the drop itself via {@link BrowserContext#setOffline} was tried first and
 * abandoned: it did not disrupt an already-open SSE stream in this environment (0 change observed
 * over a 15s/33-retry window) - plausibly because Chromium's offline emulation only intercepts new
 * outgoing requests, not already-established long-lived connections.
 */
@ExtendWith(DashboardE2eEnvironment.class)
class ReconnectE2eTest {

  @Test
  @Timeout(30)
  void nativeEventSourceReconnectsAfterAMidRunDrop(Page page, BrowserContext context) {
    AtomicInteger requestCount = new AtomicInteger(0);

    context.route(
        "**/api/v1/runs/*/events",
        route -> {
          int callNumber = requestCount.incrementAndGet();
          String runId = extractRunId(route.request().url());

          String body = callNumber == 1 ? firstDropBody(runId) : resumedBody(runId);
          route.fulfill(
              new Route.FulfillOptions()
                  .setStatus(200)
                  .setHeaders(Map.of("Content-Type", "text/event-stream"))
                  .setBody(body));
        });

    // Only this run's /events is hijacked - its real lifecycle against the real public target
    // keeps executing in the background regardless. DashboardE2eEnvironment.afterTestExecution's
    // centralized best-effort cancel takes care of not leaving it to contend with later tests' own
    // real runs, so this test needs no cleanup of its own for that (see GapReplayE2eTest).
    RunDetailsPage details =
        RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL).launchRun();

    details.waitForConnectionState("Connection lost — reconnecting…", Duration.ofSeconds(15));
    details.waitForConnectionState("Run finished.", Duration.ofSeconds(10));

    assertThat(requestCount.get()).isEqualTo(2);
    assertThat(details.testRow("a()").getByText("PASSED")).isVisible();
  }

  private static String extractRunId(String eventsUrl) {
    return eventsUrl.replaceAll(".*/runs/([^/]+)/events.*", "$1");
  }

  /**
   * Ends after 2 events, deliberately with no {@code RUN_FINISHED} - a legitimate mid-run drop for
   * native EventSource to notice and auto-reconnect from, not a sequence gap.
   */
  private static String firstDropBody(String runId) {
    return sseFrame(1, "RUN_QUEUED", runQueuedJson(runId, 1))
        + sseFrame(2, "RUN_STARTED", runStartedJson(runId, 2));
  }

  /**
   * Continues gaplessly from sequence 3 - what a real server's own Last-Event-ID-aware replay would
   * send back, not a repeat of 1-2.
   */
  private static String resumedBody(String runId) {
    return sseFrame(3, "TEST_STARTED", testEventJson(runId, 3, "TEST_STARTED", "test-a", "a()"))
        + sseFrame(4, "TEST_PASSED", testEventJson(runId, 4, "TEST_PASSED", "test-a", "a()"))
        + sseFrame(5, "RUN_FINISHED", runFinishedJson(runId, 5));
  }

  private static String sseFrame(int sequence, String type, String json) {
    return "id:" + sequence + "\n" + "event:" + type + "\n" + "data:" + json + "\n\n";
  }

  private static String runQueuedJson(String runId, int sequence) {
    return """
        {"schemaVersion":"%s","runId":"%s","sequence":%d,"timestamp":"2026-09-01T10:00:00Z","type":"RUN_QUEUED"}"""
        .formatted(RunnerEvent.CURRENT_SCHEMA_VERSION, runId, sequence);
  }

  private static String runStartedJson(String runId, int sequence) {
    return """
        {"schemaVersion":"%s","runId":"%s","sequence":%d,"timestamp":"2026-09-01T10:00:01Z","type":"RUN_STARTED"}"""
        .formatted(RunnerEvent.CURRENT_SCHEMA_VERSION, runId, sequence);
  }

  private static String testEventJson(
      String runId, int sequence, String type, String testId, String testDisplayName) {
    return """
        {"schemaVersion":"%s","runId":"%s","sequence":%d,"timestamp":"2026-09-01T10:00:02Z","type":"%s","testId":"%s","testDisplayName":"%s"}"""
        .formatted(
            RunnerEvent.CURRENT_SCHEMA_VERSION, runId, sequence, type, testId, testDisplayName);
  }

  private static String runFinishedJson(String runId, int sequence) {
    return """
        {"schemaVersion":"%s","runId":"%s","sequence":%d,"timestamp":"2026-09-01T10:00:03Z","type":"RUN_FINISHED","runOutcome":"SUCCEEDED"}"""
        .formatted(RunnerEvent.CURRENT_SCHEMA_VERSION, runId, sequence);
  }
}
