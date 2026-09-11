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
import java.time.Duration;
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
 * The single component owning the whole {@code runs}/{@code run_events} atomic sequence: {@code
 * SELECT ... FOR UPDATE} &rarr; validate/apply the transition &rarr; allocate {@code sequence} from
 * the same locked row &rarr; insert the event &rarr; update the run &rarr; commit, all in one
 * transaction - true cross-table atomicity needs this, not two independently-lockable
 * collaborators.
 *
 * <p>{@code SELECT ... FOR UPDATE} only serializes concurrent database writers on the same {@code
 * runId} and releases at {@code COMMIT}, so it is not by itself the "publish after commit, before
 * unlock" guarantee the in-process {@code RunEventHub} subscribe path needs - that requires an
 * external per-run lock wrapping both this store's commit and the Hub publish. Every write method
 * here returns the exact {@link RunnerEvent} committed (via {@link CommittedRunChange}) so that
 * lock wrapper never has to re-derive or re-read anything.
 *
 * <p>Every write method validates its caller-supplied event factory's output before it reaches SQL
 * (see {@link RunEventValidation}), since a mismatched {@code runId}/sequence or a type/outcome
 * that doesn't match the transition would otherwise silently corrupt the row/event correlation.
 *
 * <p>{@link Run}'s timestamp columns are truncated to microseconds ({@link #truncateToMicros})
 * since {@code TIMESTAMPTZ} only stores that precision - otherwise a nanosecond {@link Instant}
 * would make the returned {@link Run} disagree with a later re-read. {@link
 * RunnerEvent#timestamp()} is exempt: it's stored verbatim in the authoritative {@code jsonb
 * payload}, with a truncated copy only in the secondary {@code occurred_at} index column.
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
   * transition}, optionally allocates and inserts one event, and updates the row - all in one
   * transaction. Returns empty without writing anything when the run is already terminal (a benign
   * lost race).
   *
   * @param eventFactory builds the event for the allocated sequence, or {@code null} to transition
   *     with no event at all.
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
   * Appends exactly one {@code TEST_*}/{@code STEP_*} event under the same row lock as every other
   * write here, without touching the run's status, timestamps, or {@code version} - only {@code
   * next_event_sequence} advances. Kept separate from {@link #transitionIfNonTerminal}: an identity
   * transition would still run a full {@code UPDATE runs} for every test/step event, which carries
   * no lifecycle change at all. Returns empty, appending nothing, once the run is already terminal.
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

  /**
   * The {@code cleanup_started_at IS NULL} filter is what makes a tombstoned run disappear from
   * every public read path (including SSE replay) immediately once cleanup starts, not only once
   * its files are actually gone.
   */
  @Override
  public Optional<Run> findById(String runId) {
    List<RunRow> matches =
        jdbcTemplate.query(
            "SELECT * FROM runs WHERE run_id = ? AND cleanup_started_at IS NULL",
            (rs, rowNum) -> toRunRow(rs),
            runId);
    return matches.stream().findFirst().map(row -> row.toRun(selectedTestsFor(runId)));
  }

  /** Loads every run's own selected tests in one query, not one query per run (avoids N+1). */
  @Override
  public List<Run> findAll() {
    List<RunRow> rows =
        jdbcTemplate.query(
            "SELECT * FROM runs WHERE cleanup_started_at IS NULL ORDER BY requested_at DESC",
            (rs, rowNum) -> toRunRow(rs));
    if (rows.isEmpty()) {
      return List.of();
    }
    Map<String, List<SelectedTestSnapshot>> selectedByRunId = allSelectedTestsGroupedByRunId();
    return rows.stream()
        .map(row -> row.toRun(selectedByRunId.getOrDefault(row.runId(), List.of())))
        .toList();
  }

  /**
   * A literal {@code IN} list, not bound query parameters: PostgreSQL's partial-index predicate
   * matching happens during planning against constant expressions, not placeholders, so a bound-
   * parameter form can't reliably prove it implies {@code idx_runs_non_terminal}'s predicate. These
   * three statuses are fixed, never caller-supplied, so a literal list is safe here.
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
   * The literal set {@code chk_runs_cleanup_only_when_terminal} already enforces as a DB invariant,
   * built from {@link RunStatus#isTerminal()} so it can't drift out of sync with that enum - same
   * literal-not-bound-parameter reasoning as {@link #NON_TERMINAL_STATUS_LIST}.
   */
  private static final String TERMINAL_STATUS_LIST =
      java.util.Arrays.stream(RunStatus.values())
          .filter(RunStatus::isTerminal)
          .map(s -> "'" + s.name() + "'")
          .collect(java.util.stream.Collectors.joining(", "));

  /**
   * Backs {@code RunRecoveryService}'s startup pass with a dedicated, indexed query instead of
   * {@link #findAll} plus a Java-side filter, so recovery time scales with the (normally tiny)
   * number of runs left to recover, not the whole run history.
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
   * Backs {@code RunEventBroker#replayAndSubscribe}'s replay half. Deserializes each row's {@code
   * payload} (jsonb) via the same {@link ObjectMapper} every write uses - the column values are a
   * secondary index, never re-parsed here.
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

  /** See {@code RunLifecycleStore}'s Javadoc for the exact eligibility rule. */
  @Override
  public List<String> findEligibleForCleanup(Instant now, Duration maxAge, int maxCount) {
    Instant cutoff = now.minus(maxAge);
    return jdbcTemplate.query(
        """
        SELECT run_id FROM (
          SELECT run_id, finished_at,
                 ROW_NUMBER() OVER (
                   ORDER BY finished_at DESC, requested_at DESC, run_id DESC
                 ) AS rnk
          FROM runs
          WHERE cleanup_started_at IS NULL AND status IN (%s)
        ) ranked
        WHERE rnk > ? OR finished_at < ?
        """
            .formatted(TERMINAL_STATUS_LIST),
        (rs, rowNum) -> rs.getString("run_id"),
        maxCount,
        Timestamp.from(cutoff));
  }

  @Override
  public List<String> findPendingCleanup() {
    return jdbcTemplate.query(
        "SELECT run_id FROM runs WHERE cleanup_started_at IS NOT NULL",
        (rs, rowNum) -> rs.getString("run_id"));
  }

  @Override
  public boolean claimForCleanup(String runId) {
    int updated =
        jdbcTemplate.update(
            "UPDATE runs SET cleanup_started_at = now() WHERE run_id = ? AND cleanup_started_at"
                + " IS NULL AND status IN ("
                + TERMINAL_STATUS_LIST
                + ")",
            runId);
    return updated > 0;
  }

  @Override
  public void deleteRun(String runId) {
    jdbcTemplate.update("DELETE FROM runs WHERE run_id = ?", runId);
  }

  @Override
  public List<String> findEligibleForArtifactPurge(Instant now, Duration maxAge) {
    Instant cutoff = now.minus(maxAge);
    return jdbcTemplate.query(
        "SELECT run_id FROM runs WHERE artifacts_purge_started_at IS NULL AND status IN ("
            + TERMINAL_STATUS_LIST
            + ") AND finished_at < ?",
        (rs, rowNum) -> rs.getString("run_id"),
        Timestamp.from(cutoff));
  }

  @Override
  public List<String> findPendingArtifactPurge() {
    return jdbcTemplate.query(
        "SELECT run_id FROM runs WHERE artifacts_purge_started_at IS NOT NULL AND"
            + " artifacts_purged_at IS NULL",
        (rs, rowNum) -> rs.getString("run_id"));
  }

  @Override
  public boolean claimForArtifactPurge(String runId) {
    int updated =
        jdbcTemplate.update(
            "UPDATE runs SET artifacts_purge_started_at = now() WHERE run_id = ? AND"
                + " artifacts_purge_started_at IS NULL AND status IN ("
                + TERMINAL_STATUS_LIST
                + ")",
            runId);
    return updated > 0;
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
