package dev.vlaisanem.automation.dashboarde2e;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for a review's finding: a stale process (or any unrelated service) already bound
 * to the target health endpoint must never be mistaken for a freshly launched one - see {@link
 * DashboardProcess#refuseIfAlreadyAnswering}. Discovered live in this repo's own history - a
 * forgotten manual run left the exact backend/dashboard ports occupied, and the health poll happily
 * accepted the leftover process as the freshly started instance.
 */
class DashboardProcessTest {

  @Test
  void refusesToStartWhenSomethingIsAlreadyAnsweringTheHealthEndpoint(@TempDir Path workingDir)
      throws IOException {
    HttpServer staleServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    staleServer.createContext("/health", exchange -> respond(exchange, 200));
    staleServer.start();
    try {
      String healthUrl = "http://127.0.0.1:" + staleServer.getAddress().getPort() + "/health";

      // A command that would only ever fail with an IOException from ProcessBuilder.start()
      // itself (a nonexistent executable) - if refuseIfAlreadyAnswering's preflight check did NOT
      // run first, this assertion would see that IOException instead of the expected
      // IllegalStateException, proving the process launch was never actually reached.
      List<String> commandThatMustNeverRun = List.of("this-executable-does-not-exist-3f8a2c1d");

      assertThatThrownBy(
              () ->
                  DashboardProcess.start(
                      "test",
                      commandThatMustNeverRun,
                      workingDir,
                      Map.of(),
                      healthUrl,
                      Duration.ofSeconds(5)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("already answering");
    } finally {
      staleServer.stop(0);
    }
  }

  private static void respond(HttpExchange exchange, int status) throws IOException {
    exchange.sendResponseHeaders(status, -1);
    exchange.close();
  }
}
