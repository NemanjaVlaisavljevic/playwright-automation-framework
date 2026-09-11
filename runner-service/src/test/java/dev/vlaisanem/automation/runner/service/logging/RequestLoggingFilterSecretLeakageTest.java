package dev.vlaisanem.automation.runner.service.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vlaisanem.automation.runner.service.artifacts.ArtifactRepository;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Sends a real request carrying sentinel secrets in {@code Authorization}, {@code Cookie}, an OAuth
 * {@code code}/{@code state} query parameter, and the body, then proves none reach the captured
 * access-log event - verifying {@link RequestLoggingFilter}'s "only method/route/status/duration"
 * design against a running app, not just by reading its source.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
      "spring.datasource.hikari.initialization-fail-timeout=-1"
    })
class RequestLoggingFilterSecretLeakageTest {

  private static final String AUTH_SENTINEL = "Bearer super-secret-token-sentinel";
  private static final String COOKIE_SENTINEL = "JSESSIONID=cookie-value-sentinel";
  private static final String OAUTH_CODE_SENTINEL = "oauth-code-sentinel";
  private static final String OAUTH_STATE_SENTINEL = "oauth-state-sentinel";
  private static final String BODY_SENTINEL = "request-body-sentinel";

  @MockitoBean private RunLifecycleStore lifecycleStore;
  @MockitoBean private ArtifactRepository artifactRepository;
  @MockitoBean private DiskUsageService diskUsageService;

  @Value("${local.server.port}")
  private int port;

  private Logger logger;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void attachLogAppender() {
    logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    logger.addAppender(logAppender);
  }

  @AfterEach
  void detachLogAppender() {
    logger.detachAppender(logAppender);
  }

  @Test
  void noSentinelSecretEverReachesTheCapturedAccessLogEvent() throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + port
                        + "/api/v1/auth/oauth2/callback/github?code="
                        + OAUTH_CODE_SENTINEL
                        + "&state="
                        + OAUTH_STATE_SENTINEL))
            .header("Authorization", AUTH_SENTINEL)
            .header("Cookie", COOKIE_SENTINEL)
            .POST(HttpRequest.BodyPublishers.ofString("{\"secret\":\"" + BODY_SENTINEL + "\"}"))
            .build();

    client.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(logAppender.list).isNotEmpty();
    for (ILoggingEvent event : logAppender.list) {
      String rendered = event.getFormattedMessage() + event.getKeyValuePairs();
      assertThat(rendered)
          .doesNotContain(AUTH_SENTINEL)
          .doesNotContain(COOKIE_SENTINEL)
          .doesNotContain(OAUTH_CODE_SENTINEL)
          .doesNotContain(OAUTH_STATE_SENTINEL)
          .doesNotContain(BODY_SENTINEL);
    }
  }
}
