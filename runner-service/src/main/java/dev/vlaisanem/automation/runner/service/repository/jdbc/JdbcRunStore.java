package dev.vlaisanem.automation.runner.service.repository.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.domain.TestLayer;
import dev.vlaisanem.automation.runner.service.repository.CommittedRunChange;
import dev.vlaisanem.automation.runner.service.repository.RunEventValidation;
import dev.vlaisanem.automation.runner.service.repository.RunLifecycleStore;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D2.2 (docs/DEPLOYMENT_ARCHITECTURE.md section 3) - the single component that owns the whole
 * {@code runs}/{@code run_events} atomic sequence the replay-atomicity protocol requires: {@code
 * SELECT ... FOR UPDATE} &rarr; validate/apply the transition &rarr; allocate {@code sequence} from
 * the same locked row &rarr; insert the event &rarr; update the run &rarr; commit. Reviewed finding
 * this class exists specifically to satisfy: the old {@code RunRepository}/{@code RunEventAppender}
 * split (a repository holding an in-process per-key lock, calling out to a separate event-appending
 * collaborator inside its {@code beforeCommit}) cannot be mechanically carried over to Postgres,
 * because true cross-table atomicity requires one transaction, not two independently-lockable
 * collaborators.
 *
 * <p><strong>What the DB row lock does and does not give you (a second review round corrected this
 * class's own earlier claim here)</strong>: {@code SELECT ... FOR UPDATE} serializes concurrent
 * <em>database writers</em> on the same {@code runId} - two transactions racing to finalize the
 * same run can never corrupt each other's sequence allocation or row update, which is everything
 * this class's own tests prove. It is released at {@code COMMIT}, though, so it is <em>not</em> by
 * itself the complete per-run lock the protocol's "publish after commit, before unlock" rule needs
 * - that rule is about coordinating this store's commit with the live in-process {@code
 * RunEventHub} subscribe path, which a DB row lock cannot reach at all. D2.3 must wrap a call into
 * this store <em>and</em> the resulting Hub publish inside one external, in-process per-run lock,
 * shared with {@code Subscribe} - exactly the design this class deliberately supports rather than
 * pre-empts: every write method here returns a {@link CommittedRunChange} carrying the exact {@link
 * RunnerEvent} that was actually committed (not just the {@link Run} snapshot), so that future lock
 * wrapper has everything it needs to publish without re-deriving or re-reading anything.
 *
 * <p><strong>Wired into the live application as of D2.3</strong>: a real Spring {@code @Component}
 * now that {@code DataSourceAutoConfiguration}/{@code FlywayAutoConfiguration} are no longer
 * excluded on {@code RunnerServiceApplication} - {@link JdbcTemplate} comes from Spring Boot's own
 * {@code JdbcTemplateAutoConfiguration}, {@link TransactionTemplate} from {@code
 * TransactionAutoConfiguration}'s {@code TransactionTemplateConfiguration} (given the single {@code
 * PlatformTransactionManager} {@code DataSourceTransactionManagerAutoConfiguration} creates for the
 * auto-configured {@code DataSource}), and {@link ObjectMapper} from the existing {@code
 * spring-boot-starter-json} autoconfiguration already relied on elsewhere in this service - no
 * manual {@code @Bean} wiring needed for any of the three. {@code databaseIntegrationTest} still
 * constructs its own instance directly against a Testcontainers Postgres, entirely independent of
 * this Spring wiring.
 *
 * <p>Every write method validates the event its caller-supplied factory produces before it ever
 * reaches SQL: the factory receives the sequence this class allocated and is trusted to build a
 * {@link RunnerEvent} for it, but a factory that returns a mismatched {@code runId}/{@code
 * sequence}, or an event type/outcome that does not match the lifecycle transition actually being
 * recorded, would otherwise silently corrupt the row/event correlation (the {@code run_id} column
 * and the JSON payload's own {@code runId} could disagree; {@code next_event_sequence} could be
 * incremented once while a stale sequence number lands in the payload). See {@link
 * {@code RunEventValidation#requireMatchingEvent}/{@code RunEventValidation#requireLifecycleEventMatches}.
 *
 * <p>Only {@link Run}'s own timestamp columns ({@code requestedAt}/{@code startedAt}/{@code
 * finishedAt}, backed by {@code TIMESTAMPTZ}) are truncated to microseconds ({@link
 * #truncateToMicros}) before use - {@code TIMESTAMPTZ} only stores microsecond precision, so a
 * caller-supplied {@link Instant} with finer (nanosecond) precision would otherwise make the
 * in-memory {@link Run}/{@link CommittedRunChange} this class returns disagree with what a later
 * {@link #findById} re-read of the same row produces. Truncating once, at the point each value is
 * first used, keeps every returned {@link Run} byte-for-byte consistent with what is actually
 * persisted from the very start, rather than only after an explicit round trip. This does
 * <strong>not</strong> extend to {@link RunnerEvent#timestamp()}: it is stored twice - verbatim, at
 * full nanosecond precision, inside the {@code jsonb payload} (the authoritative copy, round-tripped
 * exactly through {@link ObjectMapper}), and separately, truncated to microseconds, in {@code
 * run_events.occurred_at} (a secondary index column only, never re-parsed back into a {@link
 * RunnerEvent} - see {@link #readEventsAfter}/{@link #latestEvent}, which read {@code payload}
 * alone).
 */
@Component
public class JdbcRunStore implements RunLifecycleStore {

  private final JdbcTemplate jdbcTemplate;
  private final TransactionTemplate transactionTemplate;
  private final ObjectMapper objectMapper;

  public JdbcRunStore(
      JdbcTemplate jdbcTemplate,
      TransactionTemplate transactionTemplate,
      ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionTemplate = transactionTemplate;
    this.objectMapper = objectMapper;
  }

  /**
   * Durably accepts a new run and emits its {@code RUN_QUEUED} - always the first event, always
   * sequence 1.
   */
  @Override
  public CommittedRunChange queue(
      String runId,
      Environment environment,
      Suite suite,
      Instant requestedAt,
      List<SelectedTestSnapshot> selectedTests,
      LongFunction<RunnerEvent> queuedEventFactory) {
    Objects.requireNonNull(queuedEventFactory, "queuedEventFactory must not be null");
    Instant truncatedRequestedAt = truncateToMicros(requestedAt);
    Run run = Run.queued(runId, environment, suite, truncatedRequestedAt, selectedTests);
    return transactionTemplate.execute(
        status -> {
          jdbcTemplate.update(
              """
              INSERT INTO runs (run_id, environment, suite, status, requested_at)
              VALUES (?, ?, ?, ?, ?)
              """,
              runId,
              environment.name(),
              suite.name(),
              run.status().name(),
              Timestamp.from(truncatedRequestedAt));
          int ordinal = 0;
          for (SelectedTestSnapshot selected : run.selectedTests()) {
            jdbcTemplate.update(
                """
                INSERT INTO run_selected_tests (run_id, ordinal, test_key, display_name, layer)
                VALUES (?, ?, ?, ?, ?)
                """,
                runId,
                ordinal++,
                selected.testKey(),
                selected.displayName(),
                selected.layer().name());
          }
          RunnerEvent queuedEvent = queuedEventFactory.apply(1L);
          RunEventValidation.requireQueuedEvent(queuedEvent, runId);
          insertEvent(runId, queuedEvent);
          jdbcTemplate.update("UPDATE runs SET next_event_sequence = 2 WHERE run_id = ?", runId);
          return new CommittedRunChange(run, queuedEvent);
        });
  }

  /**
   * Atomically re-reads {@code runId} under {@code SELECT ... FOR UPDATE}, applies {@code
   * transition} (via {@link Run#transitionTo}, which validates through {@code RunStateMachine} the
   * same way every other caller of that method already does), optionally allocates and inserts one
   * event, and updates the row - all inside one transaction. Returns empty without writing anything
   * when the run is already terminal (a benign lost race, mirroring {@code
   * RunRepository#transitionIfNonTerminal}'s own contract), and never allocates a sequence or
   * inserts an event in that case either.
   *
   * @param eventFactory builds the event for the allocated sequence, or {@code null} to transition
   *     with no event at all (mirrors {@code RunLifecycleCoordinator#markStarting}, which
   *     deliberately emits nothing). When present, the produced event must actually match the
   *     transition being recorded - see {@code RunEventValidation#requireLifecycleEventMatches}.
   * @throws NoSuchElementException if no run exists for {@code runId}.
   */
  @Override
  public Optional<CommittedRunChange> transitionIfNonTerminal(
      String runId, UnaryOperator<Run> transition, LongFunction<RunnerEvent> eventFactory) {
    Objects.requireNonNull(transition, "transition must not be null");
    return transactionTemplate.execute(
        status -> {
          RunRow current = selectForUpdate(runId);
          if (current.status().isTerminal()) {
            return Optional.empty();
          }
          Run before = current.toRun(selectedTestsFor(runId));
          Run updated = transition.apply(before);
          RunEventValidation.requireSameIdentity(before, updated);
          RunEventValidation.requireReachableTransition(before, updated);
          long nextSequence = current.nextEventSequence();
          RunnerEvent committedEvent =
              eventFactory == null ? null : eventFactory.apply(nextSequence);
          if (committedEvent != null) {
            RunEventValidation.requireMatchingEvent(committedEvent, runId, nextSequence);
          }
          RunEventValidation.requireLifecycleEventMatches(committedEvent, updated.status());
          if (committedEvent != null) {
            insertEvent(runId, committedEvent);
            nextSequence += 1;
          }
          Run truncatedUpdated = truncateTimestamps(updated);
          updateRun(runId, truncatedUpdated, nextSequence);
          return Optional.of(new CommittedRunChange(truncatedUpdated, committedEvent));
        });
  }

  /**
   * Appends exactly one {@code TEST_*}/{@code STEP_*} event under the same {@code SELECT ... FOR
   * UPDATE} row lock as every other write here, without touching the run's own status, timestamps,
   * or {@code version} - only {@code next_event_sequence} advances. A separate method from {@link
   * #transitionIfNonTerminal} on purpose, per review: reusing an identity transition as a
   * workaround would still run a full {@code UPDATE runs} (bumping {@code version} and rewriting
   * every lifecycle column) for every single test/step event a run ever produces, which is wrong -
   * this method's whole point is that a test/step event carries no lifecycle change at all. Returns
   * empty, appending nothing, once the run is already terminal - a run's canonical timeline may
   * carry no event after its own {@code RUN_FINISHED} (mirrors {@code RunEventAppender}'s own
   * already-closed-journal contract, the same invariant enforced here instead).
   *
   * @throws NoSuchElementException if no run exists for {@code runId}.
   */
  @Override
  public Optional<RunnerEvent> appendEventIfNonTerminal(
      String runId, LongFunction<RunnerEvent> eventFactory) {
    Objects.requireNonNull(eventFactory, "eventFactory must not be null");
    return transactionTemplate.execute(
        status -> {
          RunRow current = selectForUpdate(runId);
          if (current.status().isTerminal()) {
            return Optional.empty();
          }
          long sequence = current.nextEventSequence();
          RunnerEvent event = eventFactory.apply(sequence);
          RunEventValidation.requireMatchingEvent(event, runId, sequence);
          RunEventValidation.requireAppendOnlyEventType(event);
          insertEvent(runId, event);
          jdbcTemplate.update(
              "UPDATE runs SET next_event_sequence = ? WHERE run_id = ?", sequence + 1, runId);
          return Optional.of(event);
        });
  }

  @Override
  public Optional<Run> findById(String runId) {
    List<RunRow> matches =
        jdbcTemplate.query(
            "SELECT * FROM runs WHERE run_id = ?", (rs, rowNum) -> toRunRow(rs), runId);
    return matches.stream().findFirst().map(row -> row.toRun(selectedTestsFor(runId)));
  }

  /**
   * Loads every run's own selected tests in one query, not one query per run - a review finding:
   * the original version called {@link #selectedTestsFor} once per row, an N+1 pattern that gets
   * expensive as run history grows, for a query this class's own contract already documents as
   * "sort/list everything."
   */
  @Override
  public List<Run> findAll() {
    List<RunRow> rows =
        jdbcTemplate.query(
            "SELECT * FROM runs ORDER BY requested_at DESC", (rs, rowNum) -> toRunRow(rs));
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<String, List<SelectedTestSnapshot>> selectedByRunId = allSelectedTestsGroupedByRunId();
    return rows.stream()
        .map(row -> row.toRun(selectedByRunId.getOrDefault(row.runId(), List.of())))
        .toList();
  }

  /**
   * D2.5 review [P2] - the {@code IN (?, ?, ?)} form this originally used bound the three statuses
   * as query parameters, which a generic prepared-statement plan (PostgreSQL's own planner may
   * switch to one after a handful of executions) cannot reliably prove implies {@code
   * idx_runs_non_terminal}'s own literal predicate - partial-index predicate matching happens
   * during planning, against constant expressions, not parameter placeholders (see PostgreSQL's own
   * partial indexes documentation). Since these three statuses are a fixed part of the recovery
   * protocol, never caller-supplied, this literal {@code IN} list matches the migration's predicate
   * exactly instead, so the planner can always use the index regardless of which plan it picks.
   * Verified via {@code EXPLAIN} against a real table (see {@code
   * RunRecoveryServiceJdbcAcceptanceTest}).
   */
  private static final String NON_TERMINAL_STATUS_LIST =
      "'"
          + RunStatus.QUEUED.name()
          + "', '"
          + RunStatus.STARTING.name()
          + "', '"
          + RunStatus.RUNNING.name()
          + "'";

  /**
   * D2.5 - backs {@code RunRecoveryService}'s startup pass with a dedicated, indexed query (see
   * {@code idx_runs_non_terminal}) instead of {@link #findAll} plus a Java-side filter, which would
   * otherwise load every historical run's own {@code run_selected_tests} just to discard the
   * terminal majority of them - recovery time would then grow with the whole run history instead of
   * with the (normally tiny) number of runs actually left to recover.
   */
  @Override
  public List<Run> findNonTerminal() {
    List<RunRow> rows =
        jdbcTemplate.query(
            "SELECT * FROM runs WHERE status IN ("
                + NON_TERMINAL_STATUS_LIST
                + ") ORDER BY requested_at",
            (rs, rowNum) -> toRunRow(rs));
    return rows.stream().map(row -> row.toRun(selectedTestsFor(row.runId()))).toList();
  }

  /**
   * D2.3 - backs {@code RunEventBroker#replayAndSubscribe}'s replay half, reading from {@code
   * run_events} instead of the file-backed journal. Deserializes each row's own {@code payload}
   * (jsonb) back into a {@link RunnerEvent} via the same {@link ObjectMapper} every write goes
   * through - the column values ({@code event_type}/{@code occurred_at}) are a secondary index, not
   * re-parsed here, since the payload alone is the complete, authoritative record.
   */
  @Override
  public List<RunnerEvent> readEventsAfter(String runId, long afterSequence) {
    return jdbcTemplate.query(
        "SELECT payload FROM run_events WHERE run_id = ? AND sequence > ? ORDER BY sequence",
        (rs, rowNum) -> readJson(rs.getString("payload")),
        runId,
        afterSequence);
  }

  @Override
  public Optional<RunnerEvent> latestEvent(String runId) {
    List<RunnerEvent> matches =
        jdbcTemplate.query(
            "SELECT payload FROM run_events WHERE run_id = ? ORDER BY sequence DESC LIMIT 1",
            (rs, rowNum) -> readJson(rs.getString("payload")),
            runId);
    return matches.stream().findFirst();
  }

  private RunnerEvent readJson(String payload) {
    try {
      return objectMapper.readValue(payload, RunnerEvent.class);
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Failed to deserialize run_events payload: " + payload, exception);
    }
  }

  private Map<String, List<SelectedTestSnapshot>> allSelectedTestsGroupedByRunId() {
    Map<String, List<SelectedTestSnapshot>> grouped = new LinkedHashMap<>();
    jdbcTemplate.query(
        "SELECT run_id, test_key, display_name, layer FROM run_selected_tests ORDER BY run_id, ordinal",
        rs -> {
          grouped
              .computeIfAbsent(rs.getString("run_id"), ignored -> new ArrayList<>())
              .add(
                  new SelectedTestSnapshot(
                      rs.getString("test_key"),
                      rs.getString("display_name"),
                      TestLayer.valueOf(rs.getString("layer"))));
        });
    return grouped;
  }

  private RunRow selectForUpdate(String runId) {
    List<RunRow> matches =
        jdbcTemplate.query(
            "SELECT * FROM runs WHERE run_id = ? FOR UPDATE", (rs, rowNum) -> toRunRow(rs), runId);
    return matches.stream()
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("No run found for runId: " + runId));
  }

  private List<SelectedTestSnapshot> selectedTestsFor(String runId) {
    return jdbcTemplate.query(
        "SELECT test_key, display_name, layer FROM run_selected_tests WHERE run_id = ? ORDER BY ordinal",
        (rs, rowNum) ->
            new SelectedTestSnapshot(
                rs.getString("test_key"),
                rs.getString("display_name"),
                TestLayer.valueOf(rs.getString("layer"))),
        runId);
  }

  private void updateRun(String runId, Run run, long nextEventSequence) {
    jdbcTemplate.update(
        """
        UPDATE runs
        SET status = ?, started_at = ?, finished_at = ?, exit_code = ?, detail = ?,
            next_event_sequence = ?, version = version + 1, updated_at = now()
        WHERE run_id = ?
        """,
        run.status().name(),
        toTimestamp(run.startedAt()),
        toTimestamp(run.finishedAt()),
        run.exitCode(),
        run.detail(),
        nextEventSequence,
        runId);
  }

  private void insertEvent(String runId, RunnerEvent event) {
    jdbcTemplate.update(
        """
        INSERT INTO run_events (run_id, sequence, event_type, occurred_at, payload)
        VALUES (?, ?, ?, ?, ?::jsonb)
        """,
        runId,
        event.sequence(),
        event.type().name(),
        Timestamp.from(event.timestamp()),
        writeJson(event));
  }

  private String writeJson(RunnerEvent event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize " + event, exception);
    }
  }

  private static Timestamp toTimestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  /** {@code TIMESTAMPTZ} only stores microsecond precision - see the class Javadoc. */
  private static Instant truncateToMicros(Instant instant) {
    return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
  }

  private static Run truncateTimestamps(Run run) {
    return new Run(
        run.runId(),
        run.environment(),
        run.suite(),
        run.status(),
        truncateToMicros(run.requestedAt()),
        truncateToMicros(run.startedAt()),
        truncateToMicros(run.finishedAt()),
        run.exitCode(),
        run.detail(),
        run.selectedTests());
  }

  private RunRow toRunRow(ResultSet rs) throws SQLException {
    return new RunRow(
        rs.getString("run_id"),
        Environment.valueOf(rs.getString("environment")),
        Suite.valueOf(rs.getString("suite")),
        RunStatus.valueOf(rs.getString("status")),
        rs.getTimestamp("requested_at").toInstant(),
        toInstant(rs.getTimestamp("started_at")),
        toInstant(rs.getTimestamp("finished_at")),
        (Integer) rs.getObject("exit_code"),
        rs.getString("detail"),
        rs.getLong("next_event_sequence"));
  }

  private static Instant toInstant(java.sql.Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  /** One raw {@code runs} row, before its {@code run_selected_tests} are joined in. */
  private record RunRow(
      String runId,
      Environment environment,
      Suite suite,
      RunStatus status,
      Instant requestedAt,
      Instant startedAt,
      Instant finishedAt,
      Integer exitCode,
      String detail,
      long nextEventSequence) {

    Run toRun(List<SelectedTestSnapshot> selectedTests) {
      return new Run(
          runId,
          environment,
          suite,
          status,
          requestedAt,
          startedAt,
          finishedAt,
          exitCode,
          detail,
          selectedTests);
    }
  }
}
