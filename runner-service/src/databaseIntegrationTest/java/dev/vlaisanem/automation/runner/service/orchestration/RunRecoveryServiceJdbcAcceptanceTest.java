package dev.vlaisanem.automation.runner.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunOutcome;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.artifacts.ArtifactIngestionService;
import dev.vlaisanem.automation.runner.service.artifacts.FakeArtifactRepository;
import dev.vlaisanem.automation.runner.service.config.RateLimitRule;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.events.RunEventBroker;
import dev.vlaisanem.automation.runner.service.exception.RunnerRecoveringException;
import dev.vlaisanem.automation.runner.service.metrics.RunnerMetrics;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import dev.vlaisanem.automation.runner.service.repository.jdbc.JdbcRunStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies {@link RunRecoveryService} against real Postgres: non-terminal runs recover to {@code
 * ERROR}, terminal runs are untouched, repeat passes are no-ops, one run's DB-level failure fails
 * the whole pass, and {@code findNonTerminal} actually uses its partial index.
 */
@Testcontainers
class RunRecoveryServiceJdbcAcceptanceTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine");

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  private static JdbcRunStore store;
  private static String jdbcUrl;
  private static final RunnerMetrics METRICS = new RunnerMetrics(new SimpleMeterRegistry());

  @BeforeAll
  static void migrateAndBuildStore() {
    jdbcUrl = POSTGRES.getJdbcUrl();
    Flyway.configure()
        .dataSource(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();

    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(jdbcUrl);
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());

    ObjectMapper objectMapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    TransactionTemplate transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    store = new JdbcRunStore(jdbcTemplate, transactionTemplate, objectMapper);
  }

  @Test
  void recoversEveryNonTerminalRunToErrorAndLeavesTerminalRunsUntouched() {
    RunLifecycleCoordinator coordinator = newCoordinator(store);
    String queuedRun = newRunId();
    String startingRun = newRunId();
    String runningRun = newRunId();
    String succeededRun = newRunId();

    coordinator.queue(queuedRun, Environment.PUBLIC, Suite.SMOKE, NOW);

    coordinator.queue(startingRun, Environment.PUBLIC, Suite.SMOKE, NOW);
    coordinator.markStarting(startingRun, NOW);

    coordinator.queue(runningRun, Environment.PUBLIC, Suite.SMOKE, NOW);
    coordinator.markStarting(runningRun, NOW);
    coordinator.markRunning(runningRun, NOW);

    coordinator.queue(succeededRun, Environment.PUBLIC, Suite.SMOKE, NOW);
    coordinator.markStarting(succeededRun, NOW);
    coordinator.markRunning(succeededRun, NOW);
    coordinator.finishIfLive(succeededRun, RunStatus.SUCCEEDED, 0, null, NOW);

    RunRecoveryService recovery = new RunRecoveryService(store, coordinator, METRICS);
    recovery.run(null);

    for (String recoveredRunId : List.of(queuedRun, startingRun, runningRun)) {
      Run run = store.findById(recoveredRunId).orElseThrow();
      assertThat(run.status()).as(recoveredRunId).isEqualTo(RunStatus.ERROR);
      assertThat(run.detail()).as(recoveredRunId).contains("restarted");
      List<RunnerEvent> events = store.readEventsAfter(recoveredRunId, 0);
      assertThat(events.getLast().type()).as(recoveredRunId).isEqualTo(EventType.RUN_FINISHED);
      assertThat(events.getLast().runOutcome()).as(recoveredRunId).isEqualTo(RunOutcome.ERROR);
      assertThat(events.stream().filter(event -> event.type() == EventType.RUN_FINISHED))
          .as("%s must have exactly one RUN_FINISHED", recoveredRunId)
          .hasSize(1);
    }

    Run succeeded = store.findById(succeededRun).orElseThrow();
    assertThat(succeeded.status()).isEqualTo(RunStatus.SUCCEEDED);
    assertThat(store.readEventsAfter(succeededRun, 0)).hasSize(3); // QUEUED, STARTED, FINISHED
    assertThat(recovery.isRecoveryComplete()).isTrue();
  }

  @Test
  void isIdempotentOnASecondRecoveryPassAgainstRealPostgres() {
    RunLifecycleCoordinator coordinator = newCoordinator(store);
    String runningRun = newRunId();
    coordinator.queue(runningRun, Environment.PUBLIC, Suite.SMOKE, NOW);
    coordinator.markStarting(runningRun, NOW);
    coordinator.markRunning(runningRun, NOW);

    new RunRecoveryService(store, coordinator, METRICS).run(null);
    int eventCountAfterFirstPass = store.readEventsAfter(runningRun, 0).size();

    new RunRecoveryService(store, coordinator, METRICS).run(null);

    Run run = store.findById(runningRun).orElseThrow();
    assertThat(run.status()).isEqualTo(RunStatus.ERROR);
    assertThat(store.readEventsAfter(runningRun, 0)).hasSize(eventCountAfterFirstPass);
  }

  /**
   * Forces a genuine Postgres-level failure (a temporary trigger rejecting {@code badRun}'s
   * recovery UPDATE), not a test double, so fail-closed behavior is proven at the database layer.
   * Trigger is removed in {@code finally} since this class shares one static schema.
   */
  @Test
  void aFailureRecoveringOneRunAgainstRealPostgresStillFailsTheWholePassAndKeepsTheGateClosed()
      throws SQLException {
    RunLifecycleCoordinator coordinator = newCoordinator(store);
    String goodRun = newRunId();
    String badRun = newRunId();
    coordinator.queue(goodRun, Environment.PUBLIC, Suite.SMOKE, NOW);
    coordinator.markStarting(goodRun, NOW);
    coordinator.queue(badRun, Environment.PUBLIC, Suite.SMOKE, NOW);
    coordinator.markStarting(badRun, NOW);

    RunRecoveryService recovery = new RunRecoveryService(store, coordinator, METRICS);
    installFailingRecoveryTrigger(badRun);
    try {
      assertThatThrownBy(() -> recovery.run(null)).isInstanceOf(IllegalStateException.class);
    } finally {
      dropFailingRecoveryTrigger();
    }

    assertThat(store.findById(goodRun).orElseThrow().status()).isEqualTo(RunStatus.ERROR);
    assertThat(store.findById(badRun).orElseThrow().status())
        .as(
            "the run whose own recovery UPDATE failed at the database level stays at its old,"
                + " stale status")
        .isEqualTo(RunStatus.STARTING);
    assertThatThrownBy(recovery::requireRecoveryComplete)
        .isInstanceOf(RunnerRecoveringException.class);
  }

  /**
   * A parameterized {@code IN (?, ?, ?)} query isn't reliably matched by Postgres's planner to
   * {@code idx_runs_non_terminal}'s literal predicate, so {@link JdbcRunStore#findNonTerminal} uses
   * the same literal list. Proved here via {@code EXPLAIN} against a real table.
   */
  @Test
  void findNonTerminalUsesThePartialIndex() throws SQLException {
    List<String> planLines = new ArrayList<>();
    try (Connection connection =
            DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "EXPLAIN SELECT * FROM runs WHERE status IN ('QUEUED', 'STARTING', 'RUNNING')"
                    + " ORDER BY requested_at")) {
      while (resultSet.next()) {
        planLines.add(resultSet.getString(1));
      }
    }
    String plan = String.join("\n", planLines);
    assertThat(plan)
        .as("query plan must use idx_runs_non_terminal, not a sequential scan:\n%s", plan)
        .contains("idx_runs_non_terminal");
  }

  private static void installFailingRecoveryTrigger(String runId) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE OR REPLACE FUNCTION test_reject_recovery_update() RETURNS trigger AS $$
          BEGIN
            IF NEW.run_id = '%s' AND NEW.status = 'ERROR' THEN
              RAISE EXCEPTION 'simulated recovery update failure for %%', NEW.run_id;
            END IF;
            RETURN NEW;
          END;
          $$ LANGUAGE plpgsql
          """
              .formatted(runId));
      statement.execute(
          """
          CREATE TRIGGER test_reject_recovery_update_trigger
          BEFORE UPDATE ON runs
          FOR EACH ROW EXECUTE FUNCTION test_reject_recovery_update()
          """);
    }
  }

  private static void dropFailingRecoveryTrigger() throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TRIGGER IF EXISTS test_reject_recovery_update_trigger ON runs");
      statement.execute("DROP FUNCTION IF EXISTS test_reject_recovery_update()");
    }
  }

  private static RunLifecycleCoordinator newCoordinator(RunLifecycleStore store) {
    RunnerProperties properties = testProperties();
    ArtifactIngestionService artifactIngestionService =
        new ArtifactIngestionService(new ObjectMapper(), new FakeArtifactRepository(), properties);
    RunEventBroker broker =
        new RunEventBroker(
            store, properties, artifactIngestionService, METRICS, new SimpleMeterRegistry());
    return new RunLifecycleCoordinator(broker, artifactIngestionService, METRICS);
  }

  private static String newRunId() {
    return "run-" + UUID.randomUUID();
  }

  private static RunnerProperties testProperties() {
    return new RunnerProperties(
        ".",
        Duration.ofSeconds(30),
        "raw",
        "logs",
        "src/test/resources/catalog/public-test-catalog.json",
        "artifacts",
        1024 * 1024,
        Duration.ofSeconds(5),
        Duration.ofSeconds(1),
        1,
        Duration.ofMillis(150),
        Duration.ofSeconds(5),
        10_000,
        Duration.ofSeconds(15),
        Duration.ofMinutes(10),
        new RateLimitRule(5, Duration.ofMinutes(1)),
        new RateLimitRule(10, Duration.ofMinutes(1)),
        new RateLimitRule(3, Duration.ofMinutes(1)),
        new RateLimitRule(10, Duration.ofHours(1)),
        new RateLimitRule(10, Duration.ofMinutes(1)),
        new RateLimitRule(120, Duration.ofMinutes(1)),
        new RateLimitRule(30, Duration.ofMinutes(1)),
        3,
        16384,
        Duration.ofDays(30),
        500,
        Duration.ofDays(14),
        Duration.ofHours(1),
        new RateLimitRule(10, Duration.ofHours(1)),
        1_048_576L,
        26_214_400L,
        209_715_200L,
        2_097_152L,
        2_097_152L,
        104_857_600L,
        new RateLimitRule(10, Duration.ofHours(1)),
        Duration.ofSeconds(60));
  }
}
