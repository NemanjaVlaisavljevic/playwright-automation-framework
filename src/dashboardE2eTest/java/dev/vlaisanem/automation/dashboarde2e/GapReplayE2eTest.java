package dev.vlaisanem.automation.dashboarde2e;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import dev.vlaisanem.automation.dashboarde2e.pages.RunDetailsPage;
import dev.vlaisanem.automation.dashboarde2e.pages.RunsListPage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A browser-level SSE transport/recovery integration test, not a full backend E2E: the real backend
 * never produces a sequence gap in normal operation (that is exactly the point of its durable,
 * gapless journal), so this test's {@code /events} responses are synthesized via context-level
 * {@link BrowserContext#route}, not served by the real runner-service. Everything else is real - a
 * real launched run, a real browser, a real native {@code EventSource}, the real named-event
 * parsing/reducer/hook/close-old-open-new logic in the actual built frontend bundle.
 *
 * <p>{@code Route.fulfill} takes a single complete response body - there is no incremental
 * streaming API for it - so the gap is expressed as two separate, complete SSE HTTP responses
 * rather than one continuous stream with a gap injected mid-stream: the first response ends after
 * delivering a gap (sequence 1, then 3), which native {@code EventSource} treats as an ordinary
 * clean connection close and therefore auto-reconnects from - but by the time that auto-reconnect
 * clock would fire, the dashboard's own gap-retry logic has already closed that connection and
 * opened a brand-new one itself (see {@code use-run-event-stream.ts}), which this route's second
 * response answers with the correct, complete 1..N replay.
 *
 * <p>The second response is deliberately delayed a little (see {@code SECOND_RESPONSE_DELAY} below)
 * before being fulfilled - a real reopened connection to a real server always costs at least one
 * network round trip, during which the {@code "Live stream fell out of sync..."} banner this test
 * asserts on is actually visible; {@code route.fulfill()} otherwise answers in-process,
 * near-instantly, which against the production bundle (see {@code DashboardE2eEnvironment}'s own
 * {@code vite preview}, faster than the unminified {@code vite dev} build this test was written
 * against) let that banner get replaced by {@code "Run finished."} within a single render, before
 * Playwright's own polling ever observed it - confirmed live as an intermittent failure (2 of 3
 * reruns) once switched to `preview`. The delay makes the test double more realistic, not less.
 */
@ExtendWith(DashboardE2eEnvironment.class)
class GapReplayE2eTest {

  private static final Duration SECOND_RESPONSE_DELAY = Duration.ofMillis(300);

  @Test
  @Timeout(30)
  void recoversFromAGapWithExactlyOneFreshReplayAttempt(Page page, BrowserContext context) {
    AtomicInteger requestCount = new AtomicInteger(0);
    List<String> lastEventIdHeaders = new CopyOnWriteArrayList<>();

    context.route(
        "**/api/v1/runs/*/events",
        route -> {
          int callNumber = requestCount.incrementAndGet();
          String runId = extractRunId(route.request().url());
          lastEventIdHeaders.add(route.request().headers().get("last-event-id"));

          String body;
          if (callNumber == 1) {
            body = gapBody(runId);
          } else {
            sleepQuietly(SECOND_RESPONSE_DELAY);
            body = fullReplayBody(runId);
          }
          route.fulfill(
              new Route.FulfillOptions()
                  .setStatus(200)
                  .setHeaders(Map.of("Content-Type", "text/event-stream"))
                  .setBody(body));
        });

    // Only the real launched run's runId (for the synthetic /events responses to embed, so the
    // reducer's own runId check accepts them) and its real REST record (for the page's header to
    // render) matter to this test - its real lifecycle against the real public target does not. It
    // is still genuinely executing in the background the whole time below (only its *observation*
    // via /events is hijacked, never its own actual lifecycle) -
    // DashboardE2eEnvironment.afterTestExecution's centralized best-effort cancel takes care of not
    // leaving it to contend with later tests' own real runs, so this test needs no cleanup of its
    // own for that.
    RunDetailsPage details =
        RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL).launchRun();

    details.waitForConnectionState(
        "Live stream fell out of sync. Replaying from the beginning…", Duration.ofSeconds(10));
    details.waitForConnectionState("Run finished.", Duration.ofSeconds(10));

    assertThat(requestCount.get()).isEqualTo(2);
    assertThat(lastEventIdHeaders).hasSize(2);
    assertThat(lastEventIdHeaders.get(1))
        .as("the fresh-replay connection must be a brand-new EventSource, not a resumed one")
        .isNull();

    assertThat(details.testRow("a()").getByText("PASSED")).isVisible();
  }

  private static String extractRunId(String eventsUrl) {
    return eventsUrl.replaceAll(".*/runs/([^/]+)/events.*", "$1");
  }

  private static void sleepQuietly(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String gapBody(String runId) {
    return sseFrame(1, "RUN_QUEUED", runQueuedJson(runId, 1))
        + sseFrame(3, "RUN_STARTED", runStartedJson(runId, 3)); // gap: expected 2, got 3
  }

  private static String fullReplayBody(String runId) {
    return sseFrame(1, "RUN_QUEUED", runQueuedJson(runId, 1))
        + sseFrame(2, "RUN_STARTED", runStartedJson(runId, 2))
        + sseFrame(3, "TEST_STARTED", testEventJson(runId, 3, "TEST_STARTED", "test-a", "a()"))
        + sseFrame(4, "TEST_PASSED", testEventJson(runId, 4, "TEST_PASSED", "test-a", "a()"))
        + sseFrame(5, "RUN_FINISHED", runFinishedJson(runId, 5));
  }

  private static String sseFrame(int sequence, String type, String json) {
    return "id:" + sequence + "\n" + "event:" + type + "\n" + "data:" + json + "\n\n";
  }

  private static String runQueuedJson(String runId, int sequence) {
    return """
        {"schemaVersion":"1.0","runId":"%s","sequence":%d,"timestamp":"2026-09-01T10:00:00Z","type":"RUN_QUEUED"}"""
        .formatted(runId, sequence);
  }

  private static String runStartedJson(String runId, int sequence) {
    return """
        {"schemaVersion":"1.0","runId":"%s","sequence":%d,"timestamp":"2026-09-01T10:00:01Z","type":"RUN_STARTED"}"""
        .formatted(runId, sequence);
  }

  private static String testEventJson(
      String runId, int sequence, String type, String testId, String testDisplayName) {
    return """
        {"schemaVersion":"1.0","runId":"%s","sequence":%d,"timestamp":"2026-09-01T10:00:02Z","type":"%s","testId":"%s","testDisplayName":"%s"}"""
        .formatted(runId, sequence, type, testId, testDisplayName);
  }

  private static String runFinishedJson(String runId, int sequence) {
    return """
        {"schemaVersion":"1.0","runId":"%s","sequence":%d,"timestamp":"2026-09-01T10:00:03Z","type":"RUN_FINISHED","runOutcome":"SUCCEEDED"}"""
        .formatted(runId, sequence);
  }
}
