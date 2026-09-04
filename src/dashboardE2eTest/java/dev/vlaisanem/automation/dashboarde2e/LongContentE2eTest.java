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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * C4.6.3 - a synthesized SSE stream (same {@code BrowserContext#route} technique as {@code
 * GapReplayE2eTest}/{@code ReconnectE2eTest}: the real backend's own test/step names and failure
 * messages come from real, comfortably short JUnit display names and exception messages, so this is
 * the only practical way to exercise genuinely long content in a real Chromium render) carrying a
 * long test display name, a long step name, and a long multi-line failure detail (simulating a real
 * stack trace), verifying the layout never gains document-level horizontal scroll (see {@code
 * ResponsiveE2eTest}) and that the long text actually renders in full (wraps), rather than being
 * silently clipped or truncated.
 */
@ExtendWith(DashboardE2eEnvironment.class)
class LongContentE2eTest {

  private static final String LONG_TEST_NAME =
      "shouldRejectTheBookingWhenTheCheckoutDateIsBeforeTheCheckinDateAndAdditionallyVerifiesThat"
          + "TheValidationMessageDisplayedToTheGuestOnTheBookingFormIsBothAccurateAndActionableForA"
          + "RealUserTryingToUnderstandWhatWentWrongWithTheirReservationAttempt()";

  private static final String LONG_STEP_NAME =
      "assert that the confirmation banner rendered on the booking page contains the exact"
          + " guest-facing validation message the backend returned in its 400 response body, with no"
          + " truncation, mojibake, or leftover template placeholders anywhere in the rendered text";

  private static final String LONG_STEPLESS_TEST_NAME =
      "shouldAllowAnAdminToBulkArchiveEveryResolvedContactMessageOlderThanNinetyDaysWithoutAccident"
          + "allyTouchingAnyStillUnresolvedMessageEvenWhenTheTwoCategoriesAreInterleavedInTheAdminIn"
          + "boxSortOrder()";

  private static final String LONG_DETAIL =
      "org.opentest4j.AssertionFailedError: expected the confirmation banner text to equal:"
          + " \"Checkout date must be after the checkin date. Please choose a checkout date that"
          + " falls after 2026-09-10 and try again - if you believe this is an error, contact"
          + " support with reference RBP-4471298-BOOKING-VALIDATION-CHECK.\" but it was:"
          + " \"Something went wrong. Please try again later or contact our support team through the"
          + " help center if the problem keeps happening after multiple attempts across different"
          + " browsers and devices.\"\n"
          + "\tat dev.vlaisanem.automation.tests.journey.BookingJourneyTest.shouldRejectTheBookingWhen"
          + "TheCheckoutDateIsBeforeTheCheckinDate(BookingJourneyTest.java:184)\n"
          + "\tat dev.vlaisanem.automation.ui.pages.BookingFormPage.assertValidationMessage"
          + "(BookingFormPage.java:97)\n"
          + "\tat dev.vlaisanem.automation.core.AutomationExtension.invokeTestMethod"
          + "(AutomationExtension.java:212)";

  @Test
  @Timeout(30)
  void longTestStepAndFailureContentNeverCausesHorizontalPageScroll(
      Page page, BrowserContext context) {
    context.route(
        "**/api/v1/runs/*/events",
        route -> {
          String runId = extractRunId(route.request().url());
          route.fulfill(
              new Route.FulfillOptions()
                  .setStatus(200)
                  .setHeaders(Map.of("Content-Type", "text/event-stream"))
                  .setBody(streamBody(runId)));
        });

    RunDetailsPage details =
        RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL).launchRun();
    details.waitForConnectionState("Run finished.", Duration.ofSeconds(10));

    // Desktop first: the long test/step names and failure detail must be present and correctly
    // attributed before checking any layout property of them.
    assertThat(details.testRow(LONG_TEST_NAME)).isVisible();
    details.expandSteps(LONG_TEST_NAME);
    assertThat(details.stepRow(LONG_STEP_NAME)).isVisible();
    assertThat(details.stepDetailText(LONG_STEP_NAME)).contains(LONG_DETAIL);
    // A step-less test's name renders as plain cell text (see TestResultRow.tsx), a different
    // code path from the step-having test's own disclosure toggle above - must be checked too.
    assertThat(details.testRow(LONG_STEPLESS_TEST_NAME)).isVisible();

    for (int width : new int[] {320, 768, 1440}) {
      page.setViewportSize(width, 900);
      assertNoDocumentLevelHorizontalOverflow(page, width);
    }

    // A long test/step display name with no natural break points (a real JUnit default display
    // name for a no-args method is exactly this shape: `methodName()`, one unbroken "word") must
    // wrap onto multiple lines rather than stretching the Tests table's own internal scroll area
    // far past the space actually available to it.
    page.setViewportSize(1440, 900);
    double tableScrollClientWidth =
        ((Number) page.evaluate("document.querySelector('[class*=tableScroll]').clientWidth"))
            .doubleValue();
    double tableScrollWidth =
        ((Number) page.evaluate("document.querySelector('[class*=tableScroll]').scrollWidth"))
            .doubleValue();
    assertThat(tableScrollWidth)
        .as(
            "Tests table's own scrollWidth (%.0f) vs its container's clientWidth (%.0f) - a long"
                + " unbroken test name must wrap, not stretch the table far past its available"
                + " width",
            tableScrollWidth, tableScrollClientWidth)
        .isCloseTo(tableScrollClientWidth, org.assertj.core.data.Offset.offset(2.0));
  }

  private void assertNoDocumentLevelHorizontalOverflow(Page page, int width) {
    double scrollWidth =
        ((Number) page.evaluate("document.documentElement.scrollWidth")).doubleValue();
    double clientWidth =
        ((Number) page.evaluate("document.documentElement.clientWidth")).doubleValue();
    assertThat(scrollWidth)
        .as(
            "documentElement.scrollWidth (%.0f) vs clientWidth (%.0f) at viewport width %dpx with"
                + " a long test/step name and a long multi-line failure detail rendered",
            scrollWidth, clientWidth, width)
        .isLessThanOrEqualTo(clientWidth);
  }

  private static String extractRunId(String eventsUrl) {
    return eventsUrl.replaceAll(".*/runs/([^/]+)/events.*", "$1");
  }

  private static String streamBody(String runId) {
    String testId = "long-content-test";
    String steplessTestId = "long-content-stepless-test";
    String stepId = "long-content-step";
    String jsonDetail = jsonEscape(LONG_DETAIL);
    return sseFrame(1, "RUN_QUEUED", runLevelJson(runId, 1, "RUN_QUEUED"))
        + sseFrame(2, "RUN_STARTED", runLevelJson(runId, 2, "RUN_STARTED"))
        + sseFrame(
            3,
            "TEST_STARTED",
            testLevelJson(runId, 3, "TEST_STARTED", testId, LONG_TEST_NAME, null))
        + sseFrame(4, "STEP_STARTED", stepLevelJson(runId, 4, "STEP_STARTED", testId, stepId, null))
        + sseFrame(
            5, "STEP_FAILED", stepLevelJson(runId, 5, "STEP_FAILED", testId, stepId, jsonDetail))
        + sseFrame(
            6,
            "TEST_FAILED",
            testLevelJson(runId, 6, "TEST_FAILED", testId, LONG_TEST_NAME, jsonDetail))
        + sseFrame(
            7,
            "TEST_STARTED",
            testLevelJson(runId, 7, "TEST_STARTED", steplessTestId, LONG_STEPLESS_TEST_NAME, null))
        + sseFrame(
            8,
            "TEST_PASSED",
            testLevelJson(runId, 8, "TEST_PASSED", steplessTestId, LONG_STEPLESS_TEST_NAME, null))
        + sseFrame(9, "RUN_FINISHED", runFinishedJson(runId, 9));
  }

  private static String sseFrame(int sequence, String type, String json) {
    return "id:" + sequence + "\n" + "event:" + type + "\n" + "data:" + json + "\n\n";
  }

  private static String runLevelJson(String runId, int sequence, String type) {
    return """
        {"schemaVersion":"%s","runId":"%s","sequence":%d,"timestamp":"2026-09-04T10:00:00Z","type":"%s"}"""
        .formatted(RunnerEvent.CURRENT_SCHEMA_VERSION, runId, sequence, type);
  }

  private static String testLevelJson(
      String runId,
      int sequence,
      String type,
      String testId,
      String testDisplayName,
      String detail) {
    String detailField = detail == null ? "" : ",\"detail\":\"%s\"".formatted(detail);
    return """
        {"schemaVersion":"%s","runId":"%s","sequence":%d,"timestamp":"2026-09-04T10:00:01Z","type":"%s","testId":"%s","testDisplayName":"%s"%s}"""
        .formatted(
            RunnerEvent.CURRENT_SCHEMA_VERSION,
            runId,
            sequence,
            type,
            testId,
            jsonEscape(testDisplayName),
            detailField);
  }

  private static String stepLevelJson(
      String runId, int sequence, String type, String testId, String stepId, String detail) {
    String detailField = detail == null ? "" : ",\"detail\":\"%s\"".formatted(detail);
    return """
        {"schemaVersion":"%s","runId":"%s","sequence":%d,"timestamp":"2026-09-04T10:00:02Z","type":"%s","testId":"%s","testDisplayName":"%s","stepId":"%s","stepName":"%s"%s}"""
        .formatted(
            RunnerEvent.CURRENT_SCHEMA_VERSION,
            runId,
            sequence,
            type,
            testId,
            jsonEscape(LONG_TEST_NAME),
            stepId,
            jsonEscape(LONG_STEP_NAME),
            detailField);
  }

  private static String runFinishedJson(String runId, int sequence) {
    return """
        {"schemaVersion":"%s","runId":"%s","sequence":%d,"timestamp":"2026-09-04T10:00:03Z","type":"RUN_FINISHED","runOutcome":"FAILED"}"""
        .formatted(RunnerEvent.CURRENT_SCHEMA_VERSION, runId, sequence);
  }

  private static String jsonEscape(String raw) {
    return raw.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\t", "\\t");
  }
}
