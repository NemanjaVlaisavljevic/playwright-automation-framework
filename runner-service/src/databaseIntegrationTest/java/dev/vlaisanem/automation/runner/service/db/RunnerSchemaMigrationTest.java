package dev.vlaisanem.automation.runner.service.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * D2.1 - proves {@code V1__create_runner_schema.sql} is correct against a real PostgreSQL, not just
 * readable SQL: a fresh container migrates cleanly, migrating twice is a no-op, and every
 * constraint the migration declares actually rejects what it claims to. Deliberately a real {@code
 * postgres} Testcontainers image, never H2 - see this module's own {@code build.gradle} comment and
 * docs/DEPLOYMENT_ARCHITECTURE.md's "Technology choices" for why.
 *
 * <p>One container shared across most test methods via {@code @Container} (JUnit 5's
 * {@code @Testcontainers} extension starts it once before any test and stops it once after all of
 * them) - each such method runs inside its own transaction, rolled back afterward (see {@link
 * #withConnection}), so methods never see each other's rows without needing a fresh container per
 * method. Only {@link #migratesCleanlyThenASecondMigrationIsANoOp} needs its own dedicated,
 * separate container, since it specifically asserts on {@code migrationsExecuted} counts that only
 * mean something against a container nothing else has already migrated - reviewed finding: an
 * earlier version of this class started a *third* container for that assertion alone (one shared,
 * two separate "fresh" ones) - merged into one now.
 */
@Testcontainers
class RunnerSchemaMigrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine");

  private Flyway flyway() {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load();
  }

  @Test
  void migratesCleanlyThenASecondMigrationIsANoOp() throws SQLException {
    try (PostgreSQLContainer<?> fresh = new PostgreSQLContainer<>("postgres:17-alpine")) {
      fresh.start();
      Flyway freshFlyway =
          Flyway.configure()
              .dataSource(fresh.getJdbcUrl(), fresh.getUsername(), fresh.getPassword())
              .load();

      MigrateResult first = freshFlyway.migrate();
      assertThat(first.success).isTrue();
      assertThat(first.migrationsExecuted).isEqualTo(1);
      assertThat(tableNames(fresh))
          .containsExactlyInAnyOrder(
              "runs", "run_selected_tests", "run_events", "artifacts", "flyway_schema_history");

      MigrateResult second = freshFlyway.migrate();
      assertThat(second.success).isTrue();
      assertThat(second.migrationsExecuted).isZero();
    }
  }

  @Test
  void deletingARunCascadesToItsEventsSelectionsAndArtifacts() throws SQLException {
    flyway().migrate();

    withConnection(
        connection -> {
          insertValidRun(connection, "run-cascade");
          insertSelectedTest(connection, "run-cascade", 0, "some.Test#methodOne", "API");
          insertEvent(connection, "run-cascade", 1);
          insertArtifact(connection, "run-cascade", "artifact-cascade");

          try (PreparedStatement statement =
              connection.prepareStatement("DELETE FROM runs WHERE run_id = ?")) {
            statement.setString(1, "run-cascade");
            statement.executeUpdate();
          }

          assertThat(countWhere(connection, "run_selected_tests", "run-cascade")).isZero();
          assertThat(countWhere(connection, "run_events", "run-cascade")).isZero();
          assertThat(countWhere(connection, "artifacts", "run-cascade")).isZero();
        });
  }

  /**
   * One case per named constraint (or, for the four artifact NOT NULL columns the P1 review finding
   * added, the column name Postgres's own NOT NULL violation message names instead of a constraint)
   * - every constraint this migration declares is exercised here, not just the ones an earlier pass
   * happened to think of.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("constraintViolations")
  void rejectsEveryDeclaredConstraintViolation(
      String description, ConnectionAction action, String expectedMessageFragment) {
    flyway().migrate();

    assertThatThrownBy(() -> withConnection(action))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining(expectedMessageFragment);
  }

  private static Stream<Arguments> constraintViolations() {
    OffsetDateTime requested = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    OffsetDateTime beforeRequested = requested.minusSeconds(1);
    OffsetDateTime afterRequested = requested.plusSeconds(10);
    OffsetDateTime beforeStarted = afterRequested.minusSeconds(1);

    return Stream.of(
        // Deliberately asserts the generic "chk_runs_" prefix, not "chk_runs_status" specifically:
        // a status value outside the known set also fails to satisfy either disjunct of
        // chk_runs_started_at_by_status/chk_runs_finished_at_by_status (neither the "required"
        // nor the "forbidden" branch's IN-list matches an unrecognized value, so both evaluate to
        // false) - it therefore violates more than one of this table's CHECK constraints at once
        // (real defense in depth, not a bug), and PostgreSQL does not guarantee which one it
        // reports first. Proving it's rejected by one of *this schema's own* constraints, not
        // pinning down exactly which, is what actually matters here.
        Arguments.of(
            "unknown run status",
            (ConnectionAction)
                connection -> insertRun(connection, RunRow.basic("r-status", "NOT_A_REAL_STATUS")),
            "chk_runs_"),
        Arguments.of(
            "next_event_sequence not positive",
            (ConnectionAction)
                connection ->
                    insertRun(
                        connection, RunRow.basic("r-sequence", "QUEUED").withNextEventSequence(0)),
            "chk_runs_next_event_sequence"),
        Arguments.of(
            "version negative",
            (ConnectionAction)
                connection ->
                    insertRun(connection, RunRow.basic("r-version", "QUEUED").withVersion(-1)),
            "chk_runs_version"),
        Arguments.of(
            "RUNNING without startedAt",
            (ConnectionAction)
                connection ->
                    insertRun(
                        connection,
                        new RunRow(
                            "r-running-no-start",
                            "RUNNING",
                            requested,
                            null,
                            null,
                            null,
                            null,
                            1,
                            0)),
            "chk_runs_started_at_by_status"),
        Arguments.of(
            "QUEUED with startedAt present",
            (ConnectionAction)
                connection ->
                    insertRun(
                        connection,
                        new RunRow(
                            "r-queued-with-start",
                            "QUEUED",
                            requested,
                            afterRequested,
                            null,
                            null,
                            null,
                            1,
                            0)),
            "chk_runs_started_at_by_status"),
        Arguments.of(
            "terminal status without finishedAt",
            (ConnectionAction)
                connection ->
                    insertRun(
                        connection,
                        new RunRow(
                            "r-terminal-no-finish",
                            "SUCCEEDED",
                            requested,
                            afterRequested,
                            null,
                            0,
                            null,
                            1,
                            0)),
            "chk_runs_finished_at_by_status"),
        Arguments.of(
            "RUNNING with finishedAt present",
            (ConnectionAction)
                connection ->
                    insertRun(
                        connection,
                        new RunRow(
                            "r-running-with-finish",
                            "RUNNING",
                            requested,
                            afterRequested,
                            afterRequested,
                            null,
                            null,
                            1,
                            0)),
            "chk_runs_finished_at_by_status"),
        Arguments.of(
            "QUEUED with an exitCode",
            (ConnectionAction)
                connection ->
                    insertRun(
                        connection,
                        new RunRow(
                            "r-queued-with-result",
                            "QUEUED",
                            requested,
                            null,
                            null,
                            0,
                            null,
                            1,
                            0)),
            "chk_runs_result_only_when_terminal"),
        Arguments.of(
            "startedAt before requestedAt",
            (ConnectionAction)
                connection ->
                    insertRun(
                        connection,
                        new RunRow(
                            "r-start-before-requested",
                            "RUNNING",
                            requested,
                            beforeRequested,
                            null,
                            null,
                            null,
                            1,
                            0)),
            "chk_runs_started_at_after_requested"),
        Arguments.of(
            "finishedAt before requestedAt",
            (ConnectionAction)
                connection ->
                    insertRun(
                        connection,
                        new RunRow(
                            "r-finish-before-requested",
                            "CANCELLED",
                            requested,
                            null,
                            beforeRequested,
                            null,
                            null,
                            1,
                            0)),
            "chk_runs_finished_at_after_requested"),
        Arguments.of(
            "finishedAt before startedAt",
            (ConnectionAction)
                connection ->
                    insertRun(
                        connection,
                        new RunRow(
                            "r-finish-before-start",
                            "SUCCEEDED",
                            requested,
                            afterRequested,
                            beforeStarted,
                            0,
                            null,
                            1,
                            0)),
            "chk_runs_finished_at_after_started"),
        Arguments.of(
            "run_selected_tests ordinal negative",
            (ConnectionAction)
                connection -> {
                  insertValidRun(connection, "r-selected-ordinal");
                  insertSelectedTest(
                      connection, "r-selected-ordinal", -1, "some.Test#methodOne", "API");
                },
            "chk_run_selected_tests_ordinal"),
        Arguments.of(
            "run_selected_tests unknown layer",
            (ConnectionAction)
                connection -> {
                  insertValidRun(connection, "r-selected-layer");
                  insertSelectedTest(
                      connection, "r-selected-layer", 0, "some.Test#methodOne", "NOT_A_LAYER");
                },
            "chk_run_selected_tests_layer"),
        Arguments.of(
            "run_selected_tests duplicate testKey for the same run",
            (ConnectionAction)
                connection -> {
                  insertValidRun(connection, "r-selected-duplicate");
                  insertSelectedTest(
                      connection, "r-selected-duplicate", 0, "some.Test#methodOne", "API");
                  insertSelectedTest(
                      connection, "r-selected-duplicate", 1, "some.Test#methodOne", "API");
                },
            "uq_run_selected_tests_test_key"),
        Arguments.of(
            "run_selected_tests orphan run_id",
            (ConnectionAction)
                connection ->
                    insertSelectedTest(
                        connection, "orphaned-run-id", 0, "some.Test#methodOne", "API"),
            "fk_run_selected_tests_run"),
        Arguments.of(
            "run_events sequence not positive",
            (ConnectionAction)
                connection -> {
                  insertValidRun(connection, "r-event-sequence");
                  insertEvent(connection, "r-event-sequence", 0);
                },
            "chk_run_events_sequence"),
        Arguments.of(
            "run_events orphan run_id",
            (ConnectionAction) connection -> insertEvent(connection, "orphaned-run-id", 1),
            "fk_run_events_run"),
        Arguments.of(
            "artifacts orphan run_id",
            (ConnectionAction)
                connection -> insertArtifact(connection, "orphaned-run-id", "artifact-orphan"),
            "fk_artifacts_run"),
        Arguments.of(
            "artifacts unknown type",
            (ConnectionAction)
                connection -> {
                  insertValidRun(connection, "r-artifact-type");
                  insertArtifactWithType(
                      connection, "r-artifact-type", "artifact-bad-type", "NOT_A_TYPE");
                },
            "chk_artifacts_type"),
        Arguments.of(
            "artifacts negative size",
            (ConnectionAction)
                connection -> {
                  insertValidRun(connection, "r-artifact-size");
                  insertArtifactWithSize(
                      connection, "r-artifact-size", "artifact-negative-size", -1);
                },
            "chk_artifacts_size_bytes"),
        Arguments.of(
            "artifacts null test_id",
            (ConnectionAction)
                connection -> {
                  insertValidRun(connection, "r-artifact-null-test-id");
                  insertArtifactMissingColumn(
                      connection, "r-artifact-null-test-id", "artifact-null-test-id", "test_id");
                },
            "test_id"),
        Arguments.of(
            "artifacts null test_display_name",
            (ConnectionAction)
                connection -> {
                  insertValidRun(connection, "r-artifact-null-display-name");
                  insertArtifactMissingColumn(
                      connection,
                      "r-artifact-null-display-name",
                      "artifact-null-display-name",
                      "test_display_name");
                },
            "test_display_name"),
        Arguments.of(
            "artifacts null schema_version",
            (ConnectionAction)
                connection -> {
                  insertValidRun(connection, "r-artifact-null-schema-version");
                  insertArtifactMissingColumn(
                      connection,
                      "r-artifact-null-schema-version",
                      "artifact-null-schema-version",
                      "schema_version");
                },
            "schema_version"),
        Arguments.of(
            "artifacts null created_at",
            (ConnectionAction)
                connection -> {
                  insertValidRun(connection, "r-artifact-null-created-at");
                  insertArtifactMissingColumn(
                      connection,
                      "r-artifact-null-created-at",
                      "artifact-null-created-at",
                      "created_at");
                },
            "created_at"));
  }

  private interface ConnectionAction {
    void run(Connection connection) throws SQLException;
  }

  /**
   * Runs {@code action} inside its own transaction, always rolled back afterward - so a
   * constraint-violation case (which must fail before ever reaching the rollback line itself) still
   * leaves the shared container's schema clean for the next test method.
   */
  private static void withConnection(ConnectionAction action) throws SQLException {
    try (Connection connection = newConnection()) {
      connection.setAutoCommit(false);
      try {
        action.run(connection);
      } finally {
        connection.rollback();
      }
    }
  }

  private static Connection newConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /** One row's worth of every column {@code runs} accepts, for the lifecycle-constraint cases. */
  private record RunRow(
      String runId,
      String status,
      OffsetDateTime requestedAt,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      Integer exitCode,
      String detail,
      long nextEventSequence,
      long version) {

    static RunRow basic(String runId, String status) {
      return new RunRow(
          runId,
          status,
          OffsetDateTime.parse("2026-01-01T00:00:00Z"),
          null,
          null,
          null,
          null,
          1,
          0);
    }

    RunRow withNextEventSequence(long value) {
      return new RunRow(
          runId, status, requestedAt, startedAt, finishedAt, exitCode, detail, value, version);
    }

    RunRow withVersion(long value) {
      return new RunRow(
          runId,
          status,
          requestedAt,
          startedAt,
          finishedAt,
          exitCode,
          detail,
          nextEventSequence,
          value);
    }
  }

  private static void insertValidRun(Connection connection, String runId) throws SQLException {
    insertRun(connection, RunRow.basic(runId, "QUEUED"));
  }

  private static void insertRun(Connection connection, RunRow row) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO runs
              (run_id, environment, suite, status, requested_at, started_at, finished_at,
               exit_code, detail, next_event_sequence, version)
            VALUES (?, 'PUBLIC', 'SMOKE', ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
      statement.setString(1, row.runId());
      statement.setString(2, row.status());
      statement.setObject(3, row.requestedAt());
      statement.setObject(4, row.startedAt());
      statement.setObject(5, row.finishedAt());
      if (row.exitCode() != null) {
        statement.setInt(6, row.exitCode());
      } else {
        statement.setNull(6, Types.INTEGER);
      }
      statement.setString(7, row.detail());
      statement.setLong(8, row.nextEventSequence());
      statement.setLong(9, row.version());
      statement.executeUpdate();
    }
  }

  private static void insertSelectedTest(
      Connection connection, String runId, int ordinal, String testKey, String layer)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO run_selected_tests (run_id, ordinal, test_key, display_name, layer)
            VALUES (?, ?, ?, ?, ?)
            """)) {
      statement.setString(1, runId);
      statement.setInt(2, ordinal);
      statement.setString(3, testKey);
      statement.setString(4, testKey);
      statement.setString(5, layer);
      statement.executeUpdate();
    }
  }

  private static void insertEvent(Connection connection, String runId, long sequence)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO run_events (run_id, sequence, event_type, occurred_at, payload)
            VALUES (?, ?, 'RUN_QUEUED', now(), '{}'::jsonb)
            """)) {
      statement.setString(1, runId);
      statement.setLong(2, sequence);
      statement.executeUpdate();
    }
  }

  private static void insertArtifact(Connection connection, String runId, String artifactId)
      throws SQLException {
    insertArtifactRow(connection, runId, artifactId, "SCREENSHOT", 100, null);
  }

  private static void insertArtifactWithType(
      Connection connection, String runId, String artifactId, String type) throws SQLException {
    insertArtifactRow(connection, runId, artifactId, type, 100, null);
  }

  private static void insertArtifactWithSize(
      Connection connection, String runId, String artifactId, long sizeBytes) throws SQLException {
    insertArtifactRow(connection, runId, artifactId, "SCREENSHOT", sizeBytes, null);
  }

  /** Omits exactly one required column, to prove its own NOT NULL constraint. */
  private static void insertArtifactMissingColumn(
      Connection connection, String runId, String artifactId, String columnToOmit)
      throws SQLException {
    insertArtifactRow(connection, runId, artifactId, "SCREENSHOT", 100, columnToOmit);
  }

  private static void insertArtifactRow(
      Connection connection,
      String runId,
      String artifactId,
      String type,
      long sizeBytes,
      String columnToOmit)
      throws SQLException {
    List<String> columns =
        new ArrayList<>(
            List.of(
                "artifact_id",
                "run_id",
                "test_id",
                "test_display_name",
                "schema_version",
                "artifact_type",
                "relative_path",
                "size_bytes",
                "media_type",
                "created_at"));
    if (columnToOmit != null) {
      columns.remove(columnToOmit);
    }
    String placeholders = String.join(", ", columns.stream().map(ignored -> "?").toList());
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO artifacts ("
                + String.join(", ", columns)
                + ") VALUES ("
                + placeholders
                + ")")) {
      int index = 1;
      for (String column : columns) {
        switch (column) {
          case "artifact_id" -> statement.setString(index, artifactId);
          case "run_id" -> statement.setString(index, runId);
          case "test_id" -> statement.setString(index, "some-test-id");
          case "test_display_name" -> statement.setString(index, "Some test");
          case "schema_version" -> statement.setString(index, "1.1");
          case "artifact_type" -> statement.setString(index, type);
          case "relative_path" -> statement.setString(index, "shot.png");
          case "size_bytes" -> statement.setLong(index, sizeBytes);
          case "media_type" -> statement.setString(index, "image/png");
          case "created_at" -> statement.setObject(index, OffsetDateTime.now());
          default -> throw new IllegalStateException("Unknown column: " + column);
        }
        index++;
      }
      statement.executeUpdate();
    }
  }

  private static int countWhere(Connection connection, String table, String runId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE run_id = ?")) {
      statement.setString(1, runId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }
  }

  private static List<String> tableNames(PostgreSQLContainer<?> container) throws SQLException {
    List<String> names = new ArrayList<>();
    try (Connection connection =
            DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")) {
      while (resultSet.next()) {
        names.add(resultSet.getString(1));
      }
    }
    return names;
  }
}
