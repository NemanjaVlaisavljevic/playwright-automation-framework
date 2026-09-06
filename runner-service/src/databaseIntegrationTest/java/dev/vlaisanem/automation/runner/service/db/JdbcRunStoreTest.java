package dev.vlaisanem.automation.runner.service.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.vlaisanem.automation.runner.contract.EventType;
import dev.vlaisanem.automation.runner.contract.RunOutcome;
import dev.vlaisanem.automation.runner.contract.RunnerEvent;
import dev.vlaisanem.automation.runner.service.domain.Environment;
import dev.vlaisanem.automation.runner.service.domain.Run;
import dev.vlaisanem.automation.runner.service.domain.RunStatus;
import dev.vlaisanem.automation.runner.service.domain.SelectedTestSnapshot;
import dev.vlaisanem.automation.runner.service.domain.Suite;
import dev.vlaisanem.automation.runner.service.domain.TestLayer;
import dev.vlaisanem.automation.runner.service.repository.CommittedRunChange;
import dev.vlaisanem.automation.runner.service.repository.jdbc.JdbcRunStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * D2.2 - proves {@link JdbcRunStore} actually delivers the atomic sequence the replay-atomicity
 * protocol requires against a real PostgreSQL: round-trips a {@code Run} (including its {@code
 * CUSTOM} selection and microsecond-truncated timestamps), allocates a gapless event sequence
 * through both the lifecycle path ({@code transitionIfNonTerminal}) and the pure append-only path
 * ({@code appendEventIfNonTerminal}), rejects an event factory that returns a mismatched identity
 * (wrong {@code runId}/{@code sequence}/type), and proves both a genuine two-connection concurrency
 * race and a genuine rollback. Not yet wired into {@code RunService}/{@code
 * RunLifecycleCoordinator} - see {@link JdbcRunStore}'s own Javadoc.
 */
@Testcontainers
class JdbcRunStoreTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:17-alpine");

  private static JdbcRunStore store;
  private static JdbcTemplate jdbcTemplate;
  private static String jdbcUrl;

  @BeforeAll
  static void migrateAndBuildStore() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();

    jdbcUrl = POSTGRES.getJdbcUrl();
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(jdbcUrl);
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());

    ObjectMapper objectMapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    jdbcTemplate = new JdbcTemplate(dataSource);
    TransactionTemplate transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    store = new JdbcRunStore(jdbcTemplate, transactionTemplate, objectMapper);
  }

  @Test
  void queueThenFindByIdRoundTripsARunWithItsCustomSelection() {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
    List<SelectedTestSnapshot> selectedTests =
        List.of(
            new SelectedTestSnapshot("some.ApiTest#methodOne", "First test", TestLayer.API),
            new SelectedTestSnapshot("some.UiTest#methodTwo", "Second test", TestLayer.UI));

    CommittedRunChange queued =
        store.queue(
            runId,
            Environment.PUBLIC,
            Suite.CUSTOM,
            requestedAt,
            selectedTests,
            seq -> RunnerEvent.runQueued(runId, seq, requestedAt));

    assertThat(queued.run().status()).isEqualTo(RunStatus.QUEUED);
    assertThat(queued.event().type()).isEqualTo(EventType.RUN_QUEUED);

    Optional<Run> found = store.findById(runId);

    assertThat(found).isPresent();
    assertThat(found.get()).isEqualTo(queued.run());
    assertThat(found.get().selectedTests()).containsExactlyElementsOf(selectedTests);
  }

  /**
   * PostgreSQL's {@code TIMESTAMPTZ} only stores microsecond precision - a review finding: a plain
   * whole-second {@link Instant} in every other test here would never have exposed a mismatch
   * between {@code queue()}'s own in-memory return value and what {@code findById()} later reads
   * back. Uses a deliberately nanosecond-precision literal and asserts both sides agree on the
   * truncated value - not just that {@code findById()} "still returns something reasonable."
   */
  @Test
  void timestampsAreTruncatedToMicrosConsistentlyInMemoryAndOnRead() {
    String runId = newRunId();
    Instant nanoPrecision = Instant.parse("2026-01-01T00:00:00.123456789Z");
    Instant expectedTruncated = Instant.parse("2026-01-01T00:00:00.123456Z");

    CommittedRunChange queued =
        store.queue(
            runId,
            Environment.PUBLIC,
            Suite.SMOKE,
            nanoPrecision,
            List.of(),
            seq -> RunnerEvent.runQueued(runId, seq, nanoPrecision));

    assertThat(queued.run().requestedAt()).isEqualTo(expectedTruncated);
    assertThat(store.findById(runId).orElseThrow().requestedAt()).isEqualTo(expectedTruncated);
  }

  @Test
  void queueInsertsARunQueuedEventThatRoundTripsThroughJson() throws Exception {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");

    store.queue(
        runId,
        Environment.PUBLIC,
        Suite.SMOKE,
        requestedAt,
        List.of(),
        seq -> RunnerEvent.runQueued(runId, seq, requestedAt));

    String payload =
        jdbcTemplate.queryForObject(
            "SELECT payload::text FROM run_events WHERE run_id = ? AND sequence = 1",
            String.class,
            runId);
    ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    RunnerEvent roundTripped = objectMapper.readValue(payload, RunnerEvent.class);

    assertThat(roundTripped).isEqualTo(RunnerEvent.runQueued(runId, 1L, requestedAt));
  }

  @Test
  void queueRejectsAnEventFactoryReturningTheWrongRunId() {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");

    assertThatThrownBy(
            () ->
                store.queue(
                    runId,
                    Environment.PUBLIC,
                    Suite.SMOKE,
                    requestedAt,
                    List.of(),
                    seq -> RunnerEvent.runQueued("a-different-run-id", seq, requestedAt)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("runId");

    assertThat(store.findById(runId)).isEmpty();
  }

  @Test
  void queueRejectsAnEventFactoryReturningTheWrongSequence() {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");

    assertThatThrownBy(
            () ->
                store.queue(
                    runId,
                    Environment.PUBLIC,
                    Suite.SMOKE,
                    requestedAt,
                    List.of(),
                    seq -> RunnerEvent.runQueued(runId, 99L, requestedAt)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence");

    assertThat(store.findById(runId)).isEmpty();
  }

  @Test
  void queueRejectsAnEventFactoryReturningTheWrongEventType() {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");

    assertThatThrownBy(
            () ->
                store.queue(
                    runId,
                    Environment.PUBLIC,
                    Suite.SMOKE,
                    requestedAt,
                    List.of(),
                    seq -> RunnerEvent.runStarted(runId, seq, requestedAt)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RUN_QUEUED");

    assertThat(store.findById(runId)).isEmpty();
  }

  @Test
  void transitionRejectsAnEventFactoryReturningTheWrongEventTypeAndRollsBack() {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
    queueAndStart(runId, requestedAt);

    Instant startedAt = requestedAt.plusSeconds(2);
    assertThatThrownBy(
            () ->
                store.transitionIfNonTerminal(
                    runId,
                    run -> run.transitionTo(RunStatus.RUNNING, startedAt),
                    // Wrong: RUNNING requires RUN_STARTED, not RUN_FINISHED.
                    seq ->
                        RunnerEvent.runFinished(runId, seq, startedAt, RunOutcome.SUCCEEDED, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RUN_STARTED");

    Run afterRejectedAttempt = store.findById(runId).orElseThrow();
    assertThat(afterRejectedAttempt.status())
        .as("the RUNNING transition must not have survived the rejected event")
        .isEqualTo(RunStatus.STARTING);
    assertEventCount(runId, 1);
  }

  @Test
  void transitionRejectsAFinishedEventWhoseOutcomeDoesNotMatchTheNewStatus() {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
    queueAndStart(runId, requestedAt);
    store.transitionIfNonTerminal(
        runId,
        run -> run.transitionTo(RunStatus.RUNNING, requestedAt.plusSeconds(2)),
        seq -> RunnerEvent.runStarted(runId, seq, requestedAt.plusSeconds(2)));

    Instant finishedAt = requestedAt.plusSeconds(3);
    assertThatThrownBy(
            () ->
                store.transitionIfNonTerminal(
                    runId,
                    run -> run.transitionTo(RunStatus.SUCCEEDED, finishedAt, 0, null),
                    // Wrong: status is SUCCEEDED but the outcome says FAILED.
                    seq ->
                        RunnerEvent.runFinished(runId, seq, finishedAt, RunOutcome.FAILED, "boom")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("outcome");

    assertThat(store.findById(runId).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
    assertEventCount(runId, 2);
  }

  @Test
  void transitionWithNoEventFactoryChangesStatusButAllocatesNoSequence() {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
    queue(runId, requestedAt);

    Optional<CommittedRunChange> result =
        store.transitionIfNonTerminal(
            runId, run -> run.transitionTo(RunStatus.STARTING, requestedAt.plusSeconds(1)), null);

    assertThat(result).isPresent();
    assertThat(result.get().run().status()).isEqualTo(RunStatus.STARTING);
    assertThat(result.get().event()).isNull();
    assertEventCount(runId, 1); // only the original RUN_QUEUED - nothing new allocated
  }

  /**
   * [P1] fix - {@code transitionIfNonTerminal} used to only validate the lifecycle event/status
   * match inside {@code if (eventFactory != null)}, so a caller passing a {@code null} factory for
   * {@code RUNNING} silently committed with no {@code RUN_STARTED} event at all. The validation is
   * now unconditional - proves the rejection and the rollback.
   */
  @Test
  void transitionToRunningWithoutAnEventFactoryIsRejected() {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
    queueAndStart(runId, requestedAt);

    assertThatThrownBy(
            () ->
                store.transitionIfNonTerminal(
                    runId,
                    run -> run.transitionTo(RunStatus.RUNNING, requestedAt.plusSeconds(2)),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RUN_STARTED");

    assertThat(store.findById(runId).orElseThrow().status()).isEqualTo(RunStatus.STARTING);
    assertEventCount(runId, 1);
  }

  /** Same [P1] fix as above, for a terminal status instead of {@code RUNNING}. */
  @Test
  void transitionToATerminalStatusWithoutAnEventFactoryIsRejected() {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
    queueAndStart(runId, requestedAt);
    store.transitionIfNonTerminal(
        runId,
        run -> run.transitionTo(RunStatus.RUNNING, requestedAt.plusSeconds(2)),
        seq -> RunnerEvent.runStarted(runId, seq, requestedAt.plusSeconds(2)));

    assertThatThrownBy(
            () ->
                store.transitionIfNonTerminal(
                    runId,
                    run ->
                        run.transitionTo(RunStatus.SUCCEEDED, requestedAt.plusSeconds(3), 0, null),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RUN_FINISHED");

    assertThat(store.findById(runId).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
    assertEventCount(runId, 2);
  }

  /**
   * The other half of the same [P1] fix: {@code STARTING} must never carry an event at all - before
   * this fix, {@code requireLifecycleEventMatches} was never even invoked for {@code STARTING}, so
   * an arbitrary event attached to it would have been silently accepted and inserted.
   */
  @Test
  void transitionToStartingWithAnEventFactoryIsRejected() {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
    queue(runId, requestedAt);

    assertThatThrownBy(
            () ->
                store.transitionIfNonTerminal(
                    runId,
                    run -> run.transitionTo(RunStatus.STARTING, requestedAt.plusSeconds(1)),
                    seq -> RunnerEvent.runStarted(runId, seq, requestedAt.plusSeconds(1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not carry an event");

    assertThat(store.findById(runId).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
    assertEventCount(runId, 1);
  }

  /**
   * [P2] fix - a hand-rolled {@code UnaryOperator<Run>} is free to construct an arbitrary {@link
   * Run} instead of only changing status/timing/result; nothing in {@code Run.transitionTo} itself
   * would stop it. Proves {@code RunEventValidation#requireSameIdentity} now catches a transition
   * that rewrites {@code requestedAt} (standing in for any of the immutable identity fields) and
   * rolls the whole attempt back.
   */
  @Test
  void transitionRejectsATransitionThatChangesRunIdentity() {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
    queue(runId, requestedAt);

    assertThatThrownBy(
            () ->
                store.transitionIfNonTerminal(
                    runId,
                    run ->
                        new Run(
                            run.runId(),
                            run.environment(),
                            run.suite(),
                            RunStatus.STARTING,
                            run.requestedAt().plusSeconds(999),
                            null,
                            null,
                            null,
                            null,
                            run.selectedTests()),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("runId/environment/suite/requestedAt/selectedTests");

    assertThat(store.findById(runId).orElseThrow().requestedAt()).isEqualTo(requestedAt);
    assertThat(store.findById(runId).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
    assertEventCount(runId, 1);
  }

  @Test
  void transitionWithAnEventFactoryAllocatesTheNextGaplessSequence() {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
    queueAndStart(runId, requestedAt);

    Instant startedAt = requestedAt.plusSeconds(2);
    Optional<CommittedRunChange> result =
        store.transitionIfNonTerminal(
            runId,
            run -> run.transitionTo(RunStatus.RUNNING, startedAt),
            seq -> RunnerEvent.runStarted(runId, seq, startedAt));

    assertThat(result).isPresent();
    assertThat(result.get().event().sequence()).isEqualTo(2L);
    List<Long> sequences =
        jdbcTemplate.queryForList(
            "SELECT sequence FROM run_events WHERE run_id = ? ORDER BY sequence",
            Long.class,
            runId);
    assertThat(sequences).containsExactly(1L, 2L);
  }

  @Test
  void appendEventIfNonTerminalAllocatesTheNextSequenceWithoutChangingStatusOrVersion() {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
    queueAndStart(runId, requestedAt);
    store.transitionIfNonTerminal(
        runId,
        run -> run.transitionTo(RunStatus.RUNNING, requestedAt.plusSeconds(2)),
        seq -> RunnerEvent.runStarted(runId, seq, requestedAt.plusSeconds(2)));
    Long versionBefore =
        jdbcTemplate.queryForObject("SELECT version FROM runs WHERE run_id = ?", Long.class, runId);

    Optional<RunnerEvent> appended =
        store.appendEventIfNonTerminal(
            runId,
            seq ->
                RunnerEvent.testStarted(
                    runId, seq, requestedAt.plusSeconds(3), "test-1", "Some test"));

    assertThat(appended).isPresent();
    assertThat(appended.get().sequence()).isEqualTo(3L);
    assertThat(store.findById(runId).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
    Long versionAfter =
        jdbcTemplate.queryForObject("SELECT version FROM runs WHERE run_id = ?", Long.class, runId);
    assertThat(versionAfter)
        .as("a test/step event must not bump the lifecycle version")
        .isEqualTo(versionBefore);
    assertEventCount(runId, 3);
  }

  @Test
  void appendEventIfNonTerminalRejectsARunLevelEventType() {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
    queue(runId, requestedAt);

    assertThatThrownBy(
            () ->
                store.appendEventIfNonTerminal(
                    runId, seq -> RunnerEvent.runStarted(runId, seq, requestedAt)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TEST_*/STEP_*");

    assertEventCount(runId, 1);
  }

  @Test
  void appendEventIfNonTerminalReturnsEmptyOnceTheRunIsTerminal() {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
    queueAndStart(runId, requestedAt);
    store.transitionIfNonTerminal(
        runId,
        run -> run.transitionTo(RunStatus.RUNNING, requestedAt.plusSeconds(2)),
        seq -> RunnerEvent.runStarted(runId, seq, requestedAt.plusSeconds(2)));
    store.transitionIfNonTerminal(
        runId,
        run -> run.transitionTo(RunStatus.SUCCEEDED, requestedAt.plusSeconds(3), 0, null),
        seq ->
            RunnerEvent.runFinished(
                runId, seq, requestedAt.plusSeconds(3), RunOutcome.SUCCEEDED, null));

    Optional<RunnerEvent> appended =
        store.appendEventIfNonTerminal(
            runId,
            seq ->
                RunnerEvent.testStarted(
                    runId, seq, requestedAt.plusSeconds(4), "test-1", "Some test"));

    assertThat(appended).isEmpty();
    assertEventCount(runId, 3); // RUN_QUEUED, RUN_STARTED, RUN_FINISHED - nothing appended after
  }

  /**
   * [P2] fix - the latch-based {@link #exactlyOneOfTwoConcurrentTerminalAttemptsOnTheSameRunWins}
   * below only proves the two attempts don't corrupt each other; a scheduler could in principle
   * serialize them without either ever actually blocking on the row lock, and that test would still
   * pass. This test is the deterministic proof: it opens a second, raw JDBC connection, takes
   * {@code SELECT ... FOR UPDATE} on the row itself and deliberately holds it open (no commit),
   * then proves a concurrent {@code transitionIfNonTerminal} call genuinely cannot complete within
   * a short timeout while that lock is held - only once the locking connection commits does the
   * pending transition actually finish.
   */
  @Test
  void aSecondTransactionBlocksUntilTheFirstsRowLockIsReleased() throws Exception {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
    queueAndStart(runId, requestedAt);
    store.transitionIfNonTerminal(
        runId,
        run -> run.transitionTo(RunStatus.RUNNING, requestedAt.plusSeconds(2)),
        seq -> RunnerEvent.runStarted(runId, seq, requestedAt.plusSeconds(2)));

    ExecutorService executor = Executors.newFixedThreadPool(1);
    try (Connection lockingConnection =
        DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword())) {
      lockingConnection.setAutoCommit(false);
      try (PreparedStatement statement =
          lockingConnection.prepareStatement("SELECT * FROM runs WHERE run_id = ? FOR UPDATE")) {
        statement.setString(1, runId);
        statement.executeQuery();
      }

      Instant finishedAt = requestedAt.plusSeconds(3);
      Future<Optional<CommittedRunChange>> pendingTransition =
          executor.submit(
              () ->
                  store.transitionIfNonTerminal(
                      runId,
                      run -> run.transitionTo(RunStatus.SUCCEEDED, finishedAt, 0, null),
                      seq ->
                          RunnerEvent.runFinished(
                              runId, seq, finishedAt, RunOutcome.SUCCEEDED, null)));

      assertThatThrownBy(() -> pendingTransition.get(2, TimeUnit.SECONDS))
          .as(
              "the pending transition must still be blocked on the row lock the first connection holds")
          .isInstanceOf(TimeoutException.class);
      assertThat(store.findById(runId).orElseThrow().status())
          .as("nothing must have been applied yet while the row lock is held")
          .isEqualTo(RunStatus.RUNNING);

      lockingConnection.commit();

      Optional<CommittedRunChange> result = pendingTransition.get(10, TimeUnit.SECONDS);
      assertThat(result).isPresent();
      assertThat(store.findById(runId).orElseThrow().status()).isEqualTo(RunStatus.SUCCEEDED);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * The scenario {@code RunService}'s own cancel-racing-completion concern is really about: two
   * genuinely concurrent connections both try to finalize the same {@code RUNNING} run to a
   * different terminal status. {@code SELECT ... FOR UPDATE} means one blocks until the other
   * commits, then observes the row as already terminal and backs off - exactly one wins, matching
   * {@code RunRepository#transitionIfNonTerminal}'s own "benign lost race" contract. (This proves
   * only DB-writer serialization, not the full in-process publish lock D2.3 still owes - see {@link
   * JdbcRunStore}'s own Javadoc.) A stress companion to {@link
   * #aSecondTransactionBlocksUntilTheFirstsRowLockIsReleased}'s deterministic proof above, not a
   * replacement for it.
   */
  @Test
  void exactlyOneOfTwoConcurrentTerminalAttemptsOnTheSameRunWins() throws Exception {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
    queueAndStart(runId, requestedAt);
    store.transitionIfNonTerminal(
        runId,
        run -> run.transitionTo(RunStatus.RUNNING, requestedAt.plusSeconds(2)),
        seq -> RunnerEvent.runStarted(runId, seq, requestedAt.plusSeconds(2)));

    Instant finishedAt = requestedAt.plusSeconds(3);
    CountDownLatch bothReady = new CountDownLatch(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Optional<CommittedRunChange>> succeeded =
          executor.submit(
              () -> {
                bothReady.countDown();
                bothReady.await();
                return store.transitionIfNonTerminal(
                    runId,
                    run -> run.transitionTo(RunStatus.SUCCEEDED, finishedAt, 0, null),
                    seq ->
                        RunnerEvent.runFinished(
                            runId, seq, finishedAt, RunOutcome.SUCCEEDED, null));
              });
      Future<Optional<CommittedRunChange>> failed =
          executor.submit(
              () -> {
                bothReady.countDown();
                bothReady.await();
                return store.transitionIfNonTerminal(
                    runId,
                    run -> run.transitionTo(RunStatus.FAILED, finishedAt, 1, "boom"),
                    seq ->
                        RunnerEvent.runFinished(runId, seq, finishedAt, RunOutcome.FAILED, "boom"));
              });

      Optional<CommittedRunChange> succeededResult = succeeded.get(10, TimeUnit.SECONDS);
      Optional<CommittedRunChange> failedResult = failed.get(10, TimeUnit.SECONDS);

      // Exactly one of the two attempts applied - never both, never neither.
      assertThat(succeededResult.isPresent() ^ failedResult.isPresent()).isTrue();

      Run finalRun = store.findById(runId).orElseThrow();
      assertThat(finalRun.status()).isIn(RunStatus.SUCCEEDED, RunStatus.FAILED);
      boolean succeededWon = finalRun.status() == RunStatus.SUCCEEDED;
      assertThat(succeededResult.isPresent()).isEqualTo(succeededWon);
      assertThat(failedResult.isPresent()).isEqualTo(!succeededWon);

      // Exactly one RUN_FINISHED event was ever inserted, at sequence 3 - not zero, not two.
      Integer finishedEventCount =
          jdbcTemplate.queryForObject(
              "SELECT count(*) FROM run_events WHERE run_id = ? AND event_type = 'RUN_FINISHED'",
              Integer.class,
              runId);
      assertThat(finishedEventCount).isEqualTo(1);
      List<Long> allSequences =
          jdbcTemplate.queryForList(
              "SELECT sequence FROM run_events WHERE run_id = ? ORDER BY sequence",
              Long.class,
              runId);
      assertThat(allSequences).containsExactly(1L, 2L, 3L);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * [P1] fix - the previous version of this test attempted a {@code STARTING} transition with a
   * {@code RUN_STARTED} event factory attached. Since the D2.3 review round's [P1] fix made {@code
   * STARTING} require {@code event == null} (see {@code
   * RunEventValidation#requireLifecycleEventMatches}), that combination is now rejected by
   * Java-level validation before any SQL runs at all - the conflicting-sequence primary-key
   * violation this test exists to force was never actually reached, and the broad {@code
   * RuntimeException} assertion (an {@code IllegalArgumentException} is also a {@code
   * RuntimeException}) silently hid that the test had stopped testing what its name claims. Fixed
   * by first legitimately reaching {@code STARTING} with no event (as {@code
   * RunLifecycleCoordinator#markStarting} itself does), then forcing the conflict on the
   * <em>next</em> transition ({@code STARTING -> RUNNING} with a {@code RUN_STARTED} event, the
   * combination that combination actually requires), and asserting the concrete {@link
   * DuplicateKeyException} Spring's own exception translation produces for a Postgres primary-key
   * violation - not just "some RuntimeException was thrown".
   */
  @Test
  void aConflictingEventInsertRollsBackTheStatusChangeToo() throws SQLException {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
    queueAndStart(runId, requestedAt);
    // Reset the counter back to 1 to simulate the exact inconsistency this test needs: the next
    // event insert will collide with the RUN_QUEUED row already at sequence 1, deterministically
    // forcing the primary-key violation this test needs, without touching any other constraint.
    try (Connection connection =
            DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE runs SET next_event_sequence = 1 WHERE run_id = ?")) {
      statement.setString(1, runId);
      statement.executeUpdate();
    }

    Instant startedAt = requestedAt.plusSeconds(2);
    assertThatThrownBy(
            () ->
                store.transitionIfNonTerminal(
                    runId,
                    run -> run.transitionTo(RunStatus.RUNNING, startedAt),
                    seq -> RunnerEvent.runStarted(runId, seq, startedAt)))
        .isInstanceOf(DuplicateKeyException.class);

    Run afterFailedAttempt = store.findById(runId).orElseThrow();
    assertThat(afterFailedAttempt.status())
        .as("the RUNNING transition must not have survived the rolled-back transaction")
        .isEqualTo(RunStatus.STARTING);
    assertEventCount(runId, 1);
  }

  /**
   * [P1] fix - the acceptance matrix also calls for the opposite direction: the event insert half
   * of the transaction succeeds, but the subsequent {@code UPDATE runs} then fails - the whole
   * transaction, including that already-inserted event, must still roll back completely. Nothing in
   * this schema can naturally fail only the {@code UPDATE runs} half on demand, so this test
   * installs a temporary Postgres trigger that unconditionally rejects any {@code UPDATE} on {@code
   * runs} for the whole duration of the {@code try} block, and removes it again in a {@code
   * finally} - it must never leak into any other test sharing this class's static container/schema.
   */
  @Test
  void aFailingRunsRowUpdateRollsBackTheAlreadyInsertedEventToo() throws SQLException {
    String runId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
    queueAndStart(runId, requestedAt);

    installFailingRunsUpdateTrigger();
    try {
      Instant startedAt = requestedAt.plusSeconds(2);
      assertThatThrownBy(
              () ->
                  store.transitionIfNonTerminal(
                      runId,
                      run -> run.transitionTo(RunStatus.RUNNING, startedAt),
                      seq -> RunnerEvent.runStarted(runId, seq, startedAt)))
          .hasMessageContaining("simulated runs update failure");
    } finally {
      dropFailingRunsUpdateTrigger();
    }

    Run afterFailedAttempt = store.findById(runId).orElseThrow();
    assertThat(afterFailedAttempt.status())
        .as("the RUNNING transition must not have survived the rolled-back transaction")
        .isEqualTo(RunStatus.STARTING);
    // The RUN_STARTED event insert succeeded before the failing UPDATE - if the transaction were
    // not fully atomic, this count would be 2, not 1: the event durably committed on its own even
    // though the run's own status update failed.
    assertEventCount(runId, 1);
  }

  private static void installFailingRunsUpdateTrigger() throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE OR REPLACE FUNCTION test_reject_runs_update() RETURNS trigger AS $$
          BEGIN
            RAISE EXCEPTION 'simulated runs update failure for rollback test';
          END;
          $$ LANGUAGE plpgsql
          """);
      statement.execute(
          """
          CREATE TRIGGER test_reject_runs_update_trigger
          BEFORE UPDATE ON runs
          FOR EACH ROW EXECUTE FUNCTION test_reject_runs_update()
          """);
    }
  }

  private static void dropFailingRunsUpdateTrigger() throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TRIGGER IF EXISTS test_reject_runs_update_trigger ON runs");
      statement.execute("DROP FUNCTION IF EXISTS test_reject_runs_update()");
    }
  }

  @Test
  void findAllGroupsSelectedTestsPerRunWithoutCrossContamination() {
    String customRunId = newRunId();
    String plainRunId = newRunId();
    Instant requestedAt = Instant.parse("2026-01-01T00:00:00Z");
    List<SelectedTestSnapshot> selection =
        List.of(new SelectedTestSnapshot("some.ApiTest#methodOne", "First test", TestLayer.API));
    store.queue(
        customRunId,
        Environment.PUBLIC,
        Suite.CUSTOM,
        requestedAt,
        selection,
        seq -> RunnerEvent.runQueued(customRunId, seq, requestedAt));
    store.queue(
        plainRunId,
        Environment.PUBLIC,
        Suite.SMOKE,
        requestedAt.plusSeconds(1),
        List.of(),
        seq -> RunnerEvent.runQueued(plainRunId, seq, requestedAt.plusSeconds(1)));

    List<Run> all = store.findAll();

    Run customRun =
        all.stream().filter(r -> r.runId().equals(customRunId)).findFirst().orElseThrow();
    Run plainRun = all.stream().filter(r -> r.runId().equals(plainRunId)).findFirst().orElseThrow();
    assertThat(customRun.selectedTests()).containsExactlyElementsOf(selection);
    assertThat(plainRun.selectedTests()).isEmpty();
  }

  private static void queue(String runId, Instant requestedAt) {
    store.queue(
        runId,
        Environment.PUBLIC,
        Suite.SMOKE,
        requestedAt,
        List.of(),
        seq -> RunnerEvent.runQueued(runId, seq, requestedAt));
  }

  private static void queueAndStart(String runId, Instant requestedAt) {
    queue(runId, requestedAt);
    store.transitionIfNonTerminal(
        runId, run -> run.transitionTo(RunStatus.STARTING, requestedAt.plusSeconds(1)), null);
  }

  private static void assertEventCount(String runId, int expected) {
    Integer eventCount =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM run_events WHERE run_id = ?", Integer.class, runId);
    assertThat(eventCount).isEqualTo(expected);
  }

  private static String newRunId() {
    return "run-" + UUID.randomUUID();
  }
}
