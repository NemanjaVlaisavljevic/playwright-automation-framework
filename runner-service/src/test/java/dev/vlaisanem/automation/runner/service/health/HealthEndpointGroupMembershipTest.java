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
 * D4.3.1 - Spring Boot derives a health contributor's registered name from its *bean* name
 * (stripping a trailing {@code HealthIndicator} suffix), not from anything this codebase declares
 * explicitly - a typo or a wrong assumption in {@code application.yml}'s {@code
 * management.endpoint.health.group.readiness.include} would reference a nonexistent component,
 * which can silently omit it rather than loudly erroring. This test proves the real registered
 * names against both real probe groups, not the assumed ones - readiness's expected five-member set
 * (via the real HTTP response), and liveness's own expected single-member set (via direct {@link
 * HealthEndpointGroups} introspection - see {@link #livenessGroupContainsExactlyLivenessState()}
 * for why that one deliberately avoids HTTP).
 *
 * <p>Docker-free, real-Postgres-free, but unlike {@code OpenApiContractTest} this test does *not*
 * exclude {@code DataSourceAutoConfiguration} - the whole point is proving {@code db}'s real
 * registered contributor name, which only exists when a real {@code DataSource} bean is present.
 * {@code RunLifecycleStore}/{@code ArtifactRepository}/{@code DiskUsageService} are still mocked
 * (as in {@code OpenApiContractTest}), which keeps {@code JdbcRunStore}/{@code
 * JdbcArtifactRepository} from ever being constructed - Spring's bean-override mechanism replaces
 * their bean *definitions* before context refresh, so neither is instantiated regardless of whether
 * Postgres is reachable - so the only real consumer of the {@code DataSource} bean is Spring Boot's
 * own auto-configured {@code db} health indicator, which tolerates an unreachable database (reports
 * {@code DOWN}, a status this test never asserts on) rather than needing one. {@code
 * FlywayAutoConfiguration} stays excluded - migrations need a real, reachable, schema- correct
 * Postgres, which this test deliberately has neither. {@code hikari.initialization-fail-timeout=-1}
 * stops HikariCP's own eager startup connection check from failing context refresh outright when
 * nothing is listening on the configured (default, unreachable-in-this-test) {@code
 * localhost:5433}. {@code show-components} is overridden to {@code always} only for this one test
 * via {@code @TestPropertySource} - {@code application.yml}'s own real default ({@code never}) is
 * exactly what every other test (and production) still runs under.
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
   * D4.3.1 review finding - the readiness group's own exact-membership proof above says nothing
   * about the *liveness* group, which is what actually gates a Docker restart: {@code db}/{@code
   * disk}/{@code recovery}/{@code runnerAvailability} must never slip into it by accident (a future
   * edit to {@code application.yml} that composed them in would turn a self-resolving condition - a
   * slow Postgres, a low-disk warning, an in-progress recovery scan - into a restart-loop, exactly
   * what the liveness/readiness split exists to prevent).
   *
   * <p>Deliberately does *not* reuse the readiness test's HTTP-plus-show-components approach:
   * setting {@code management.endpoint.health.group.liveness.show-components} (even just for a
   * test) was tried first and turned out to be a genuine landmine, confirmed empirically - Spring
   * Boot's auto-configured "liveness"/"readiness" probe groups are special-cased, and touching
   * *any* per-group property on one rebinds it away from its own built-in single-member definition,
   * silently reconstituting its membership as "every registered contributor" instead (which then
   * invoked the mocked {@code DiskUsageService}'s unstubbed {@code snapshot()} and blew up with an
   * uncaught {@code NullPointerException} instead of the expected single-member body). Asserting
   * membership straight from {@link HealthEndpointGroups#get(String)}'s real {@code isMember}
   * decision - checked against every name in the real {@link HealthContributorRegistry} - proves
   * the same fact without ever touching a per-group property or invoking a single indicator's
   * {@code health()}, so it can never trigger that landmine again.
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
