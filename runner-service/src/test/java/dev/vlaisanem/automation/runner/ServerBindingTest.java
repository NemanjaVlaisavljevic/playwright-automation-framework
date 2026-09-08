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
 * Regression guard for the runner's most important safety boundary: this service can launch
 * arbitrary Gradle/Playwright processes on request, so it must default to loopback-only, never be
 * silently reachable from other machines on the network. Starts the real embedded server (not just
 * a config-property check) so a future refactor that accidentally drops or overrides {@code
 * server.address} fails loudly here, not in production.
 */
// D2.3: see OpenApiContractTest's own identical annotation for why this Docker-free, full-context
// test re-excludes DataSource/Flyway autoconfiguration and mocks out the real store.
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties =
        "spring.autoconfigure.exclude="
            + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
class ServerBindingTest {

  @MockitoBean private RunLifecycleStore lifecycleStore;
  @MockitoBean private ArtifactRepository artifactRepository;
  // D4.2: DiskUsageService also needs a JdbcTemplate and isn't behind an interface, so it must be
  // mocked here too for the same reason as the two stores above.
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
