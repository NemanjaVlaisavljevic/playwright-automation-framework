package dev.vlaisanem.automation.runner.service.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.service.artifacts.ArtifactRepository;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService.DiskUsageSnapshot;
import dev.vlaisanem.automation.runner.service.orchestration.RunService;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * D4.3.4 - closes a gap D4.3.1's own live-verification round explicitly flagged and deliberately
 * deferred: {@link RunnerAvailabilityHealthIndicator}'s exact status mapping was proven at the unit
 * level ({@code RunnerAvailabilityHealthIndicatorTest}, a mocked {@link RunService}) and its
 * registered group membership was proven separately ({@code HealthEndpointGroupMembershipTest}),
 * but nothing before this test drove a real {@code DEGRADED} {@link RunService} state through to a
 * real, live {@code /actuator/health/readiness} HTTP response - forcing an actual process-kill
 * failure live was judged "hard to safely reproduce" in D4.3.1 and waived at the time. Mocking
 * {@link RunService#isDegraded()} directly (the same established pattern {@code
 * HealthEndpointGroupMembershipTest} already uses for {@code DiskUsageService}) reaches the
 * identical real HTTP round trip without needing an actual process tree to fail termination.
 *
 * <p>Asserts the {@code runnerAvailability} <em>component's own</em> status within the readiness
 * response (via {@code show-components=always}, the same mechanism {@code
 * HealthEndpointGroupMembershipTest} uses), never the top-level aggregate - this context has no
 * real reachable Postgres, so the real (unmocked) {@code db} contributor genuinely reports {@code
 * DOWN} and would dominate the aggregate regardless of {@code runnerAvailability}'s own status,
 * which is not what this test is about.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
      "spring.datasource.hikari.initialization-fail-timeout=-1"
    })
@TestPropertySource(properties = "management.endpoint.health.show-components=always")
class RunnerAvailabilityDegradedReadinessEndToEndTest {

  @MockitoBean private RunLifecycleStore lifecycleStore;
  @MockitoBean private ArtifactRepository artifactRepository;
  @MockitoBean private DiskUsageService diskUsageService;
  @MockitoBean private RunService runService;

  @Value("${local.server.port}")
  private int port;

  @Test
  void aDegradedRunnerReportsReadinessOutOfServiceWhileLivenessStaysUp() throws Exception {
    when(diskUsageService.snapshot())
        .thenReturn(new DiskUsageSnapshot(Long.MAX_VALUE, 1_048_576L, 314_572_800L, Instant.now()));
    when(runService.isDegraded()).thenReturn(true);

    HttpResponse<String> readiness = getHealth("readiness");
    assertThat(runnerAvailabilityStatusOf(readiness)).isEqualTo("OUT_OF_SERVICE");

    HttpResponse<String> liveness = getHealth("liveness");
    assertThat(liveness.statusCode()).as("body: %s", liveness.body()).isEqualTo(200);
    assertThat(topLevelStatusOf(liveness))
        .as("a degraded runner must never be a reason to restart the JVM")
        .isEqualTo("UP");
  }

  @Test
  void anAvailableRunnerReportsReadinessUpOnceNoLongerDegraded() throws Exception {
    when(diskUsageService.snapshot())
        .thenReturn(new DiskUsageSnapshot(Long.MAX_VALUE, 1_048_576L, 314_572_800L, Instant.now()));
    when(runService.isDegraded()).thenReturn(false);

    HttpResponse<String> readiness = getHealth("readiness");
    assertThat(runnerAvailabilityStatusOf(readiness)).isEqualTo("UP");
  }

  private HttpResponse<String> getHealth(String probe) throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/" + probe))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String topLevelStatusOf(HttpResponse<String> response) throws Exception {
    JsonNode body = new ObjectMapper().readTree(response.body());
    return body.path("status").asText();
  }

  private static String runnerAvailabilityStatusOf(HttpResponse<String> response) throws Exception {
    JsonNode body = new ObjectMapper().readTree(response.body());
    JsonNode component = body.path("components").path("runnerAvailability");
    assertThat(component.isMissingNode())
        .as("readiness response never carried a runnerAvailability component: %s", response.body())
        .isFalse();
    return component.path("status").asText();
  }
}
