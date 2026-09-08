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
 * Counterpart to {@link PermissiveChainHasNoAbuseRateLimitTest} - same real embedded-Tomcat setup,
 * this time with GitHub OAuth2 credentials configured (fake, never a real GitHub round trip - the
 * same precedent {@code RunnerSecurityFailFastTest} already established), so {@code
 * SecurityConfig}'s {@code oauth2SecurityFilterChain} is the active chain instead. Proves {@link
 * AbuseRateLimitFilter} still applies its public-read limit (120/min, {@code application.yml}'s
 * {@code runner.public-read-rate-limit}) correctly on this chain, in the same real-server
 * environment where the sibling test proves the permissive chain has none - together closing the
 * review finding that a bare {@code Filter} bean's automatic servlet-container registration
 * (independent of either chain's own explicit wiring) could otherwise have applied this filter to
 * both chains, or to neither, depending on Spring Boot's own default filter ordering rather than
 * {@code SecurityConfig}'s.
 */
// D4.3.1 - no longer excludes DataSourceAutoConfiguration: application.yml's readiness group now
// unconditionally includes the `db` contributor, so a full-context test without a real DataSource
// bean fails to start at all. Flyway stays excluded and hikari.initialization-fail-timeout=-1
// stops HikariCP's own eager startup connection check from failing context refresh - see
// HealthEndpointGroupMembershipTest's own Javadoc for the full reasoning.
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
  // D4.2: DiskUsageService also needs a JdbcTemplate and isn't behind an interface, so it must be
  // mocked here too for the same reason as the two stores above.
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
   * D4.3.1 review finding - {@link
   * dev.vlaisanem.automation.runner.service.health.HealthEndpointAnonymousAccessTest} proves the
   * same shape but boots with no OAuth2 credentials configured, so {@code SecurityConfig}'s
   * <em>permissive</em> chain is the one active there - it would stay green even if {@code
   * /actuator/health/liveness}/{@code /readiness} were accidentally dropped from the real {@code
   * oauth2SecurityFilterChain}'s own {@code permitAll()} list, since the permissive chain permits
   * everything anyway. This test runs the identical anonymous-GET/no-leaked-detail assertions
   * against *this* class's real OAuth2-configured chain instead - the one every production
   * (PORTFOLIO) deployment actually runs under - so a future regression there would surface as a
   * real 401 here, not silently pass.
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
   * D4.3.2 - locked decision: {@code /actuator/prometheus} is {@code permitAll} on this real
   * OAuth2-configured chain, the same posture as the health paths above - a real Prometheus scraper
   * cannot perform an interactive GitHub OAuth2 login, so this must be reachable with no session at
   * all.
   */
  @Test
  void prometheusEndpointIsReachableAnonymouslyOnTheOAuth2Chain() throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/prometheus"))
            .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    // D4.3.2 review finding - a bare 200 alone would also pass for, say, an accidentally-served
    // SPA fallback page; proving the content type and a canonical metric name in the body confirms
    // a real Prometheus scrape payload actually arrived, not merely some 200 response.
    assertThat(response.headers().firstValue("Content-Type"))
        .hasValueSatisfying(contentType -> assertThat(contentType).contains("text/plain"));
    assertThat(response.body()).contains("runner_executor_active");
  }

  /**
   * D4.3.2 - the new {@code /actuator/prometheus} permitAll entry must not have loosened anything
   * else: every other actuator path stays denied by the same {@code anyRequest().denyAll()}
   * catch-all it always has.
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
