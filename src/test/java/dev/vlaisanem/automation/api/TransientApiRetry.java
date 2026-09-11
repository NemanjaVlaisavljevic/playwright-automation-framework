package dev.vlaisanem.automation.api;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded retry for idempotent reads against the uncontrolled shared public target. */
public final class TransientApiRetry {

  private static final Logger LOGGER = LoggerFactory.getLogger(TransientApiRetry.class);
  private static final Set<Integer> RETRYABLE_STATUSES = Set.of(500, 502, 503, 504);
  private static final List<Duration> BACKOFFS =
      List.of(Duration.ofMillis(250), Duration.ofMillis(750));

  private TransientApiRetry() {}

  /**
   * Executes once for a controlled target, or up to three times for retryable server responses from
   * the shared target. A non-retryable response is returned immediately and a persistent server
   * error remains visible to the caller after the final attempt.
   */
  public static ApiResult executeSharedTargetGet(
      Supplier<ApiResult> request, boolean targetsSharedEnvironment) {
    return executeSharedTargetGet(request, targetsSharedEnvironment, TransientApiRetry::sleep);
  }

  static ApiResult executeSharedTargetGet(
      Supplier<ApiResult> request, boolean targetsSharedEnvironment, Consumer<Duration> pause) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(pause, "pause");

    ApiResult response = Objects.requireNonNull(request.get(), "request result");
    if (!targetsSharedEnvironment) {
      return response;
    }

    for (int backoffIndex = 0;
        backoffIndex < BACKOFFS.size() && RETRYABLE_STATUSES.contains(response.status());
        backoffIndex++) {
      Duration delay = BACKOFFS.get(backoffIndex);
      LOGGER.warn(
          "Shared-target GET returned {} on attempt {}/{}; retrying in {} ms",
          response.status(),
          backoffIndex + 1,
          BACKOFFS.size() + 1,
          delay.toMillis());
      pause.accept(delay);
      response = Objects.requireNonNull(request.get(), "request result");
    }
    return response;
  }

  private static void sleep(Duration delay) {
    try {
      Thread.sleep(delay.toMillis());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting to retry shared-target GET", exception);
    }
  }
}
