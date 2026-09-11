package dev.vlaisanem.automation.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransientApiRetryTest {

  @Test
  void returnsANonRetryableResponseWithoutAnotherAttempt() {
    AtomicInteger calls = new AtomicInteger();
    List<Duration> pauses = new ArrayList<>();

    ApiResult result =
        TransientApiRetry.executeSharedTargetGet(
            () -> {
              calls.incrementAndGet();
              return result(403);
            },
            true,
            pauses::add);

    assertThat(result.status()).isEqualTo(403);
    assertThat(calls).hasValue(1);
    assertThat(pauses).isEmpty();
  }

  @Test
  void retriesTransientResponsesUntilTheExpectedResponseArrives() {
    AtomicInteger calls = new AtomicInteger();
    List<Duration> pauses = new ArrayList<>();
    List<ApiResult> responses = List.of(result(500), result(503), result(403));

    ApiResult result =
        TransientApiRetry.executeSharedTargetGet(
            () -> responses.get(calls.getAndIncrement()), true, pauses::add);

    assertThat(result.status()).isEqualTo(403);
    assertThat(calls).hasValue(3);
    assertThat(pauses).containsExactly(Duration.ofMillis(250), Duration.ofMillis(750));
  }

  @ParameterizedTest
  @ValueSource(ints = {500, 502, 503, 504})
  void retriesEveryExplicitlyAllowedTransientStatus(int transientStatus) {
    AtomicInteger calls = new AtomicInteger();
    List<Duration> pauses = new ArrayList<>();

    ApiResult result =
        TransientApiRetry.executeSharedTargetGet(
            () -> calls.getAndIncrement() == 0 ? result(transientStatus) : result(403),
            true,
            pauses::add);

    assertThat(result.status()).isEqualTo(403);
    assertThat(calls).hasValue(2);
    assertThat(pauses).containsExactly(Duration.ofMillis(250));
  }

  @Test
  void returnsTheFinalServerErrorAfterThreeAttempts() {
    AtomicInteger calls = new AtomicInteger();
    List<Duration> pauses = new ArrayList<>();

    ApiResult result =
        TransientApiRetry.executeSharedTargetGet(
            () -> {
              calls.incrementAndGet();
              return result(500);
            },
            true,
            pauses::add);

    assertThat(result.status()).isEqualTo(500);
    assertThat(calls).hasValue(3);
    assertThat(pauses).containsExactly(Duration.ofMillis(250), Duration.ofMillis(750));
  }

  @Test
  void neverRetriesAgainstAControlledTarget() {
    AtomicInteger calls = new AtomicInteger();
    List<Duration> pauses = new ArrayList<>();

    ApiResult result =
        TransientApiRetry.executeSharedTargetGet(
            () -> {
              calls.incrementAndGet();
              return result(500);
            },
            false,
            pauses::add);

    assertThat(result.status()).isEqualTo(500);
    assertThat(calls).hasValue(1);
    assertThat(pauses).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 409, 429, 501, 505})
  void doesNotRetryStatusesOutsideTheExplicitTransientSet(int status) {
    AtomicInteger calls = new AtomicInteger();

    ApiResult result =
        TransientApiRetry.executeSharedTargetGet(
            () -> {
              calls.incrementAndGet();
              return result(status);
            },
            true,
            ignored -> {});

    assertThat(result.status()).isEqualTo(status);
    assertThat(calls).hasValue(1);
  }

  private static ApiResult result(int status) {
    return new ApiResult(status, Map.of(), "");
  }
}
