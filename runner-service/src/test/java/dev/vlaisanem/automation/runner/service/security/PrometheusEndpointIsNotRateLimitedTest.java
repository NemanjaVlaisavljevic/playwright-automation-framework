package dev.vlaisanem.automation.runner.service.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vlaisanem.automation.runner.service.artifacts.ArtifactRepository;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * D4.3.2 - proves {@code /actuator/prometheus} sits genuinely outside {@link AbuseRateLimitFilter}
 * matching, not merely untested. A separate class (own context) from {@code
 * OAuth2ChainAppliesAbuseRateLimitTest} specifically so {@code runner.public-read-rate-limit} can
 * be overridden down to a small number here - that test's own 120/min real-limit assertion would
 * otherwise conflict with lowering it for this one. Cheap and deterministic: a handful of requests
 * against a 2/min override, rather than 121 requests against the real 120/min default.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
      "spring.datasource.hikari.initialization-fail-timeout=-1",
      "RUNNER_SECURITY_GITHUB_CLIENT_ID=test-client-id",
      "RUNNER_SECURITY_GITHUB_CLIENT_SECRET=test-client-secret",
      "RUNNER_SECURITY_ADMIN_GITHUB_ID=123456",
      "runner.public-read-rate-limit.max-attempts=2",
      "runner.public-read-rate-limit.window=PT1M"
    })
class PrometheusEndpointIsNotRateLimitedTest {

  @MockitoBean private RunLifecycleStore lifecycleStore;
  @MockitoBean private ArtifactRepository artifactRepository;
  @MockitoBean private DiskUsageService diskUsageService;

  @Value("${local.server.port}")
  private int port;

  @Test
  void fourRequestsPastTheLoweredPublicReadLimitAreAllStill200() throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/prometheus"))
            .build();

    List<Integer> statusCodes = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      statusCodes.add(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    assertThat(statusCodes)
        .as("all 4 requests, well past the 2/min public-read override")
        .allMatch(code -> code == 200);
  }
}
