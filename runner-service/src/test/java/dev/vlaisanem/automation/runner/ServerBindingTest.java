package dev.vlaisanem.automation.runner;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vlaisanem.automation.runner.service.artifacts.ArtifactRepository;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Regression guard for the runner's most important safety boundary: it can launch arbitrary
 * Gradle/Playwright processes, so it must default to loopback-only. Starts the real embedded
 * server, not just a config-property check, so a refactor that drops {@code server.address} fails
 * here, not in production.
 */
// See OpenApiContractTest for why Flyway is excluded but DataSourceAutoConfiguration is not (the
// readiness group needs a real `db` health contributor), and why the stores below are mocked.
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
      "spring.datasource.hikari.initialization-fail-timeout=-1"
    })
class ServerBindingTest {

  @MockitoBean private RunLifecycleStore lifecycleStore;
  @MockitoBean private ArtifactRepository artifactRepository;
  // DiskUsageService needs a real JdbcTemplate and isn't behind an interface; mocked for the same
  // reason as the stores above.
  @MockitoBean private DiskUsageService diskUsageService;

  @Value("${server.address}")
  private String configuredAddress;

  @Value("${local.server.port}")
  private int port;

  @Test
  void serverAddressDefaultsToLoopbackOnly() {
    assertThat(configuredAddress).isEqualTo("127.0.0.1");
  }

  @Test
  void embeddedServerIsActuallyReachableOnLoopback() throws IOException {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
      assertThat(socket.isConnected()).isTrue();
    }
  }
}
