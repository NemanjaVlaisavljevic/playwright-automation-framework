package dev.vlaisanem.automation.runner.service.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vlaisanem.automation.runner.service.artifacts.ArtifactRepository;
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
 * Regression test for a review finding: {@link AbuseRateLimitFilter}/{@link
 * RequestBodySizeLimitFilter}, as plain {@code Filter} beans, are also auto-registered by Spring
 * Boot as generic servlet-container filters, completely independent of - and in addition to - their
 * intended manual {@code SecurityFilterChain} wiring (see {@code ServletContextInitializerBeans}).
 * That auto-registration only exists in a real embedded servlet container, never in a {@code
 * MockMvc}-based {@code @WebMvcTest} slice (which dispatches straight to {@code
 * DispatcherServlet}), so this test deliberately boots the real application with a real embedded
 * Tomcat ({@code WebEnvironment.RANDOM_PORT}) - the one environment where the bug this class guards
 * against could actually have manifested. Pairs with {@link OAuth2ChainAppliesAbuseRateLimitTest},
 * which proves the exact same real-server setup still applies the limit on the OAuth2 chain -
 * together they prove {@code SecurityConfig}'s disabled {@code FilterRegistrationBean}s left the
 * explicit {@code SecurityFilterChain} wiring as the only place either filter ever runs, per-chain,
 * exactly as intended.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties =
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
class PermissiveChainHasNoAbuseRateLimitTest {

  @MockitoBean private RunLifecycleStore lifecycleStore;
  @MockitoBean private ArtifactRepository artifactRepository;

  @Value("${local.server.port}")
  private int port;

  /**
   * The OAuth2 chain's own public-read limit is 120/min (see {@code
   * OAuth2ChainAppliesAbuseRateLimitTest}) - sending more than that here and seeing zero {@code
   * 429}s proves the permissive/local chain (no GitHub credentials configured, the default for this
   * test and for every plain {@code bootRun}/{@code dashboardE2eTest}) genuinely has no abuse rate
   * limiting applied to it at all, not merely a limit too high to hit in this run.
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
