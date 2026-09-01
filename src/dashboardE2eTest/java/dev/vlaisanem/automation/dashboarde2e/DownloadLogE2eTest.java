package dev.vlaisanem.automation.dashboarde2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import dev.vlaisanem.automation.dashboarde2e.pages.RunDetailsPage;
import dev.vlaisanem.automation.dashboarde2e.pages.RunsListPage;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Fetches the Download log link's own {@code href} directly (via Playwright's own {@code
 * APIRequestContext}, sharing the browser context's cookies/origin) rather than driving an actual
 * file-save dialog - this is a same-origin `GET` to a plain text/log endpoint, not a download the
 * browser prompts for, so a direct request proves the link resolves to real content without needing
 * to touch the filesystem.
 */
@ExtendWith(DashboardE2eEnvironment.class)
class DownloadLogE2eTest {

  @Test
  @Timeout(90)
  void downloadLogLinkResolvesToTheRealProcessLog(Page page) {
    RunsListPage runsList = RunsListPage.open(page, DashboardE2eEnvironment.DASHBOARD_BASE_URL);
    RunDetailsPage details = runsList.launchRun();

    details.waitForStatus("SUCCEEDED", Duration.ofSeconds(60));

    String href = details.downloadLogLink().getAttribute("href");
    assertThat(href).isNotBlank();

    // href is relative (/api/v1/runs/{runId}/log) - APIRequestContext, unlike an in-page fetch(),
    // has no page origin of its own to resolve a relative URL against.
    APIResponse response = page.request().get(DashboardE2eEnvironment.DASHBOARD_BASE_URL + href);
    assertThat(response.status()).isEqualTo(200);
    String body = response.text();
    assertThat(body).isNotBlank();
    // The real Gradle test process log - not asserting on exact wording, just that it is
    // recognizably a real Gradle run's output, not an empty file or a generic error page.
    assertThat(body).containsIgnoringCase("gradle");
  }
}
