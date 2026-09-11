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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proves the real registered health-contributor names for both probe groups - Spring derives a
 * contributor's name from its bean name (stripping a trailing {@code HealthIndicator}), so a typo
 * in {@code application.yml}'s readiness include list could silently omit a member rather than
 * error. Unlike other health tests here, {@code DataSourceAutoConfiguration} is not excluded, since
 * proving {@code db}'s real name requires an actual {@code DataSource} bean.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
      "spring.datasource.hikari.initialization-fail-timeout=-1"
    })
@TestPropertySource(properties = "management.endpoint.health.show-components=always")
class HealthEndpointGroupMembershipTest {

  @MockitoBean private RunLifecycleStore lifecycleStore;
  @MockitoBean private ArtifactRepository artifactRepository;
  @MockitoBean private DiskUsageService diskUsageService;

  @Autowired private HealthEndpointGroups healthEndpointGroups;
  @Autowired private HealthContributorRegistry healthContributorRegistry;

  @Value("${local.server.port}")
  private int port;

  @Test
  void readinessGroupContainsExactlyTheExpectedComponents() throws Exception {
    when(diskUsageService.snapshot())
        .thenReturn(new DiskUsageSnapshot(Long.MAX_VALUE, 1_048_576L, 314_572_800L, Instant.now()));

    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness"))
            .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    JsonNode body = new ObjectMapper().readTree(response.body());

    JsonNode components = body.get("components");
    assertThat(components).as("readiness response: %s", response.body()).isNotNull();
    Set<String> componentNames =
        StreamSupport.stream(((Iterable<String>) components::fieldNames).spliterator(), false)
            .collect(Collectors.toSet());
    assertThat(componentNames)
        .containsExactlyInAnyOrderElementsOf(
            List.of("readinessState", "db", "recovery", "disk", "runnerAvailability"));
  }

  /**
   * Liveness must never include db/disk/recovery/runnerAvailability - composing them in would turn
   * a self-resolving condition into a restart loop. Asserts membership directly via {@link
   * HealthEndpointGroups}/{@link HealthContributorRegistry} rather than HTTP+show-components,
   * because touching any per-group property on Spring Boot's built-in liveness/readiness groups
   * rebinds them away from their single-member definition to "every registered contributor"
   * (confirmed empirically - it throws an NPE against the mocked {@code DiskUsageService}).
   */
  @Test
  void livenessGroupContainsExactlyLivenessState() {
    var liveness = healthEndpointGroups.get("liveness");
    assertThat(liveness).as("no 'liveness' health endpoint group is registered").isNotNull();

    Set<String> members =
        healthContributorRegistry.stream()
            .map(entry -> entry.name())
            .filter(liveness::isMember)
            .collect(Collectors.toSet());

    assertThat(members).containsExactly("livenessState");
  }
}
