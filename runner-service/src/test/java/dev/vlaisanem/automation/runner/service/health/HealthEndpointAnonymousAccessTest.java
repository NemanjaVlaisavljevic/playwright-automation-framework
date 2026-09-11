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
 * Proves an anonymous caller sees only a top-level {@code status}, no components or leaked details,
 * matching the real show-details/show-components=never defaults. Runs against the permissive
 * (non-OAuth2) chain; {@code OAuth2ChainAppliesAbuseRateLimitTest} covers the same shape on the
 * real production OAuth2 chain.
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
