package dev.vlaisanem.automation.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Regression guard for the runner's most important safety boundary: this service can launch
 * arbitrary Gradle/Playwright processes on request, so it must default to loopback-only, never be
 * silently reachable from other machines on the network. Starts the real embedded server (not just
 * a config-property check) so a future refactor that accidentally drops or overrides {@code
 * server.address} fails loudly here, not in production.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ServerBindingTest {

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
