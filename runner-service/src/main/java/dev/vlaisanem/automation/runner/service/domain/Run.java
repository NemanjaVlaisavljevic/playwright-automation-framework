package dev.vlaisanem.automation.runner.service.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable snapshot of one run's state at a point in time. A new snapshot is produced by {@link
 * #transitionTo}, which consults {@link RunStateMachine} so an invalid status change fails loudly
 * rather than silently overwriting history.
 *
 * <p>The compact constructor enforces which fields a given {@link RunStatus} may/must carry - these
 * instances will soon be serialized directly into REST responses, so a malformed snapshot (e.g.
 * built by hand, or deserialized from a request body) must fail at construction time rather than
 * surface as a confusing API response later.
 */
public record Run(
    String runId,
    Environment environment,
    Suite suite,
    RunStatus status,
    Instant requestedAt,
    Instant startedAt,
    Instant finishedAt,
    Integer exitCode,
    String detail) {

  public Run {
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("runId must not be blank");
    }
    Objects.requireNonNull(environment, "environment must not be null");
    Objects.requireNonNull(suite, "suite must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(requestedAt, "requestedAt must not be null");

    boolean queuedOrStarting = status == RunStatus.QUEUED || status == RunStatus.STARTING;
    boolean requiresStartedAt =
        status == RunStatus.RUNNING
            || status == RunStatus.SUCCEEDED
            || status == RunStatus.FAILED
            || status == RunStatus.TIMED_OUT;
    if (requiresStartedAt && startedAt == null) {
      throw new IllegalArgumentException(status + " requires startedAt");
    }
    if (queuedOrStarting && startedAt != null) {
      throw new IllegalArgumentException(status + " must not carry startedAt");
    }
    if (status.isTerminal() && finishedAt == null) {
      throw new IllegalArgumentException(status + " requires finishedAt");
    }
    if (!status.isTerminal() && finishedAt != null) {
      throw new IllegalArgumentException(status + " must not carry finishedAt");
    }
    if (!status.isTerminal() && (exitCode != null || detail != null)) {
      throw new IllegalArgumentException(status + " must not carry a result (exitCode/detail)");
    }
    if (startedAt != null && startedAt.isBefore(requestedAt)) {
      throw new IllegalArgumentException("startedAt must not be before requestedAt");
    }
    if (finishedAt != null && finishedAt.isBefore(requestedAt)) {
      throw new IllegalArgumentException("finishedAt must not be before requestedAt");
    }
    if (startedAt != null && finishedAt != null && finishedAt.isBefore(startedAt)) {
      throw new IllegalArgumentException("finishedAt must not be before startedAt");
    }
  }

  public static Run queued(
      String runId, Environment environment, Suite suite, Instant requestedAt) {
    return new Run(
        runId, environment, suite, RunStatus.QUEUED, requestedAt, null, null, null, null);
  }

  /** Moves to a non-terminal status ({@code STARTING}/{@code RUNNING}); no result to record yet. */
  public Run transitionTo(RunStatus newStatus, Instant now) {
    return transitionTo(newStatus, now, null, null);
  }

  /**
   * Moves to any status, optionally recording a terminal result. {@code exitCode}/{@code detail}
   * are only meaningful once {@link RunStatus#isTerminal()} - callers pass {@code null} otherwise.
   */
  public Run transitionTo(RunStatus newStatus, Instant now, Integer exitCode, String detail) {
    Objects.requireNonNull(now, "now must not be null");
    RunStateMachine.requireTransition(status, newStatus);
    return new Run(
        runId,
        environment,
        suite,
        newStatus,
        requestedAt,
        newStatus == RunStatus.RUNNING ? now : startedAt,
        newStatus.isTerminal() ? now : finishedAt,
        exitCode != null ? exitCode : this.exitCode,
        detail != null ? detail : this.detail);
  }
}
