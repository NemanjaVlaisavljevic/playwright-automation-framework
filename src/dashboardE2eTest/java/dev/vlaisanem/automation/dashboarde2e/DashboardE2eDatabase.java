package dev.vlaisanem.automation.dashboarde2e;

import java.util.Map;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * D2.3 - one Testcontainers {@code postgres:17-alpine} container, started lazily and shared for the
 * whole {@code dashboardE2eTest} JVM run: {@code RunnerServiceApplication} no longer excludes
 * {@code DataSourceAutoConfiguration}/{@code FlywayAutoConfiguration}, so every real {@code java
 * -jar} backend this suite launches (both {@code DashboardE2eEnvironment}'s shared instance and
 * {@code BackendUnavailableE2eTest}'s own isolated one) now needs a real, reachable Postgres just
 * to start at all - Flyway migrates the schema itself, on the backend's own startup, exactly as it
 * would against the real {@code deploy/docker-compose.yml} {@code postgres} service; nothing here
 * runs a migration directly.
 *
 * <p>Deliberately one shared container, not one per launched backend process: {@code
 * BackendUnavailableE2eTest} repeatedly stops and restarts its own isolated backend against the
 * <em>same</em> database on purpose - a run's history surviving that restart is exactly the kind of
 * restart-recoverability this cutover exists to prove, not an isolation leak between test classes
 * (each test class's runs are already distinguished by their own {@code runId}s).
 *
 * <p>{@link #stop} is only ever called from {@code DashboardE2eEnvironment}'s own root-context
 * close - the one point guaranteed to run after every test class in this suite has finished,
 * regardless of which one happens to start this container first. If a filtered run executes only
 * {@code BackendUnavailableE2eTest} and {@code DashboardE2eEnvironment} never starts at all,
 * nothing in this JVM calls {@link #stop} explicitly - Testcontainers' own Ryuk reaper container
 * still removes it once this JVM exits, the same safety net every other unmanaged Testcontainers
 * container in this codebase already relies on (see {@code JdbcRunStoreTest}/{@code
 * RunnerSchemaMigrationTest}'s own {@code @Container}-managed instances for the ordinary case this
 * one deliberately steps outside of, by design, to allow sharing across test classes).
 */
final class DashboardE2eDatabase {

  private static PostgreSQLContainer<?> container;

  private DashboardE2eDatabase() {}

  /**
   * Starts the shared container on first call (idempotent - a later call while it is already
   * running just returns the same connection details again) and returns the {@code
   * SPRING_DATASOURCE_*} env vars a launched {@code java -jar runner-service.jar} process needs to
   * reach it.
   */
  static synchronized Map<String, String> connectionEnv() {
    if (container == null) {
      @SuppressWarnings("resource") // stopped explicitly by stop(), not try-with-resources
      PostgreSQLContainer<?> started = new PostgreSQLContainer<>("postgres:17-alpine");
      started.start();
      container = started;
    }
    return Map.of(
        "SPRING_DATASOURCE_URL", container.getJdbcUrl(),
        "SPRING_DATASOURCE_USERNAME", container.getUsername(),
        "SPRING_DATASOURCE_PASSWORD", container.getPassword());
  }

  /** Stops the shared container, if one was ever started. Safe to call more than once. */
  static synchronized void stop() {
    if (container != null) {
      container.stop();
      container = null;
    }
  }
}
