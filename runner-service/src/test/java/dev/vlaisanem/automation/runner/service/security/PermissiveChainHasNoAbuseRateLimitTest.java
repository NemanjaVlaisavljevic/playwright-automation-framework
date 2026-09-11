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
 * {@link AbuseRateLimitFilter}/{@link RequestBodySizeLimitFilter}, as plain {@code Filter} beans,
 * are also auto-registered by Spring Boot as generic servlet-container filters, independent of
 * their intended {@code SecurityFilterChain} wiring - that only happens in a real embedded
 * container, never a {@code MockMvc}-based slice, so this boots a real embedded Tomcat ({@code
 * WebEnvironment.RANDOM_PORT}). Pairs with {@link OAuth2ChainAppliesAbuseRateLimitTest}, which
 * proves the same real-server setup still applies the limit on the OAuth2 chain - together
 * confirming {@code SecurityConfig}'s explicit {@code SecurityFilterChain} wiring is the only place
 * either filter runs, per-chain.
 */
// Flyway is excluded and Hikari's init-fail-timeout disabled so a real DataSource is required
// (readiness group needs `db`) without a live Postgres blocking context startup - see
// HealthEndpointGroupMembershipTest for details.
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
      "spring.datasource.hikari.initialization-fail-timeout=-1"
    })
class PermissiveChainHasNoAbuseRateLimitTest {

  @MockitoBean private RunLifecycleStore lifecycleStore;
  @MockitoBean private ArtifactRepository artifactRepository;
  // DiskUsageService needs a JdbcTemplate and isn't behind an interface, so it's mocked too.
  @MockitoBean private DiskUsageService diskUsageService;

  @Value("${local.server.port}")
  private int port;

  /**
   * Sends more than the OAuth2 chain's 120/min public-read limit ({@code
   * OAuth2ChainAppliesAbuseRateLimitTest}) and expects zero 429s - proves the permissive/local
   * chain (default for {@code bootRun}/{@code dashboardE2eTest}) has no rate limiting at all, not
   * merely a limit too high to hit here.
   */
  @Test
  void manyRapidRequestsNeverReceiveA429() throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/runs")).build();

    List<Integer> statusCodes = new ArrayList<>();
    for (int i = 0; i < 130; i++) {
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      statusCodes.add(response.statusCode());
    }

    assertThat(statusCodes).doesNotContain(429);
    assertThat(statusCodes).allMatch(code -> code == 200);
  }
}
