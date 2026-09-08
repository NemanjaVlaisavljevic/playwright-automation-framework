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
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
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
}
