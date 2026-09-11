package dev.vlaisanem.automation.runner.service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.service.artifacts.ArtifactRepository;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService.DiskUsageSnapshot;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Real embedded-Tomcat app with GitHub OAuth2 configured (fake credentials, no real GitHub round
 * trip), so {@code SecurityConfig}'s {@code oauth2SecurityFilterChain} is active. Proves {@link
 * AbuseRateLimitFilter} still applies its 120/min public-read limit here - paired with {@link
 * PermissiveChainHasNoAbuseRateLimitTest}, confirms a bare {@code Filter} bean's automatic
 * servlet-container registration doesn't apply it to both chains or neither.
 */
// Flyway is excluded and Hikari's init-fail-timeout disabled so a real DataSource is required
// (readiness group needs `db`) without a live Postgres blocking context startup - see
// HealthEndpointGroupMembershipTest for details.
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
      "spring.datasource.hikari.initialization-fail-timeout=-1",
      "RUNNER_SECURITY_GITHUB_CLIENT_ID=test-client-id",
      "RUNNER_SECURITY_GITHUB_CLIENT_SECRET=test-client-secret",
      "RUNNER_SECURITY_ADMIN_GITHUB_ID=123456"
    })
class OAuth2ChainAppliesAbuseRateLimitTest {

  @MockitoBean private RunLifecycleStore lifecycleStore;
  @MockitoBean private ArtifactRepository artifactRepository;
  // DiskUsageService needs a JdbcTemplate and isn't behind an interface, so it's mocked too.
  @MockitoBean private DiskUsageService diskUsageService;

  @Value("${local.server.port}")
  private int port;

  @Test
  void the121stRequestInAMinuteReceivesA429WithRetryAfter() throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/runs")).build();

    List<Integer> statusCodes = new ArrayList<>();
    HttpResponse<String> lastResponse = null;
    for (int i = 0; i < 121; i++) {
      lastResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
      statusCodes.add(lastResponse.statusCode());
    }

    assertThat(statusCodes.subList(0, 120)).allMatch(code -> code == 200);
    assertThat(statusCodes.get(120)).isEqualTo(429);
    assertThat(lastResponse.headers().firstValue("Retry-After")).isPresent();
  }

  /**
   * Runs the same anonymous-GET/no-leaked-detail assertions as {@code
   * HealthEndpointAnonymousAccessTest} but against the real OAuth2-configured chain (which every
   * production deployment runs under) - that other test boots with the permissive chain instead, so
   * it would stay green even if these paths were dropped from the oauth2 chain's own permitAll
   * list.
   */
  @ParameterizedTest
  @ValueSource(strings = {"liveness", "readiness"})
  void probeSubPathIsReachableAnonymouslyOnTheOAuth2ChainToo(String probe) throws Exception {
    when(diskUsageService.snapshot())
        .thenReturn(new DiskUsageSnapshot(Long.MAX_VALUE, 1_048_576L, 314_572_800L, Instant.now()));

    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/" + probe))
            .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    JsonNode body = new ObjectMapper().readTree(response.body());

    assertThat(response.statusCode()).as("probe: %s", probe).isIn(200, 503);
    assertThat(body.path("status").asText()).as("probe: %s", probe).isNotBlank();
    assertThat(body.has("components")).as("probe: %s, body: %s", probe, response.body()).isFalse();

    Iterator<String> fieldNames = body.fieldNames();
    List<String> topLevelKeys = new ArrayList<>();
    fieldNames.forEachRemaining(topLevelKeys::add);
    assertThat(topLevelKeys)
        .as("probe: %s, body: %s", probe, response.body())
        .containsExactly("status");
  }

  /**
   * {@code /actuator/prometheus} is {@code permitAll} on the OAuth2 chain, same posture as the
   * health paths above - a real Prometheus scraper cannot perform an interactive GitHub login.
   */
  @Test
  void prometheusEndpointIsReachableAnonymouslyOnTheOAuth2Chain() throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/prometheus"))
            .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    // A bare 200 would also pass for an accidentally-served SPA fallback page; content type + a
    // canonical metric name confirm a real Prometheus payload arrived.
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(contentType -> assertThat(contentType).contains("text/plain"));
    assertThat(response.body()).contains("runner_executor_active");
  }

  /**
   * The {@code /actuator/prometheus} permitAll entry must not have loosened anything else - every
   * other actuator path stays denied by the {@code anyRequest().denyAll()} catch-all.
   */
  @Test
  void everyOtherActuatorPathStaysDeniedOnTheOAuth2Chain() throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    for (String path : List.of("/actuator/env", "/actuator/configprops", "/actuator/beans")) {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).as("path: %s", path).isIn(401, 403);
    }
  }
}
