package dev.vlaisanem.automation.runner.service.health;

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
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * D4.3.1 - proves what an anonymous caller actually sees, against {@code application.yml}'s real
 * {@code show-details: never}/{@code show-components: never} defaults (no override here, unlike
 * {@code HealthEndpointGroupMembershipTest}) - a body with only a top-level {@code status} key, no
 * {@code components} object, and no leaked contributor name, exception message, or file-system
 * path, on both probe sub-paths. {@code db} is intentionally left reachable-or-not by chance (the
 * point of this test is response *shape*, not a specific status), so no explicit assertion is made
 * on the {@code status} value itself.
 *
 * <p>No OAuth2 credentials are configured here, so {@code SecurityConfig}'s <em>permissive</em>
 * chain is the one active - it permits everything regardless of {@code permitAll()} lists, so this
 * class alone cannot catch a regression that accidentally dropped these paths from the real {@code
 * oauth2SecurityFilterChain}. {@code
 * dev.vlaisanem.automation.runner.service.security.OAuth2ChainAppliesAbuseRateLimitTest}'s own
 * {@code probeSubPathIsReachableAnonymouslyOnTheOAuth2ChainToo} runs the identical assertions
 * against that chain instead - the one every production (PORTFOLIO) deployment actually runs under.
 *
 * <p>Same context-construction reasoning as {@code HealthEndpointGroupMembershipTest}: {@code
 * DataSourceAutoConfiguration} is not excluded (the readiness group's {@code include} list requires
 * the real {@code db} contributor to exist), {@code FlywayAutoConfiguration} stays excluded, and
 * {@code hikari.initialization-fail-timeout=-1} keeps context startup from failing when nothing is
 * listening on the configured (unreachable-in-this-test) datasource URL.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
      "spring.datasource.hikari.initialization-fail-timeout=-1"
    })
class HealthEndpointAnonymousAccessTest {

  @MockitoBean private RunLifecycleStore lifecycleStore;
  @MockitoBean private ArtifactRepository artifactRepository;
  @MockitoBean private DiskUsageService diskUsageService;

  @Value("${local.server.port}")
  private int port;

  @ParameterizedTest
  @ValueSource(strings = {"liveness", "readiness"})
  void probeSubPathLeaksNoComponentOrExceptionDetailToAnAnonymousCaller(String probe)
      throws Exception {
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
    List<String> topLevelKeys = new java.util.ArrayList<>();
    fieldNames.forEachRemaining(topLevelKeys::add);
    assertThat(topLevelKeys)
        .as("probe: %s, body: %s", probe, response.body())
        .containsExactly("status");

    List<String> forbiddenFragments =
        List.of("disk", "recovery", "runnerAvailability", "exception", "trace", "message");
    for (String fragment : forbiddenFragments) {
      assertThat(response.body())
          .as("probe: %s, body: %s", probe, response.body())
          .doesNotContainIgnoringCase(fragment);
    }
  }
}
