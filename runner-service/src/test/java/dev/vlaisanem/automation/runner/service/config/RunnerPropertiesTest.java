package dev.vlaisanem.automation.runner.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Binds against the real {@code application.yml} (not just constructing the record directly) to
 * prove the rate-limit and retention keys, including nested {@link RateLimitRule} values, bind as
 * the rest of the codebase assumes. Also covers the compact constructor's validation boundaries for
 * these fields; other fields are already exercised indirectly by the rest of this module.
 */
class RunnerPropertiesTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(TestConfig.class);

  @EnableConfigurationProperties(RunnerProperties.class)
  static class TestConfig {}

  @Test
  void bindsTheRealApplicationYmlRateLimitRulesCorrectly() {
    contextRunner.run(
        (context) -> {
          RunnerProperties properties = context.getBean(RunnerProperties.class);
          assertThat(properties.oauthAuthorizationRateLimit())
              .isEqualTo(new RateLimitRule(5, Duration.ofMinutes(1)));
          assertThat(properties.oauthCallbackRateLimit())
              .isEqualTo(new RateLimitRule(10, Duration.ofMinutes(1)));
          assertThat(properties.createRunRateLimitPerMinute())
              .isEqualTo(new RateLimitRule(3, Duration.ofMinutes(1)));
          assertThat(properties.createRunRateLimitPerHour())
              .isEqualTo(new RateLimitRule(10, Duration.ofHours(1)));
          assertThat(properties.cancelRunRateLimit())
              .isEqualTo(new RateLimitRule(10, Duration.ofMinutes(1)));
          assertThat(properties.publicReadRateLimit())
              .isEqualTo(new RateLimitRule(120, Duration.ofMinutes(1)));
          assertThat(properties.downloadRateLimit())
              .isEqualTo(new RateLimitRule(30, Duration.ofMinutes(1)));
          assertThat(properties.sseMaxConnectionsPerIp()).isEqualTo(3);
          assertThat(properties.maxRequestBodyBytes()).isEqualTo(16384L);
        });
  }

  @Test
  void bindsTheRealApplicationYmlRetentionKeysCorrectly() {
    contextRunner.run(
        (context) -> {
          RunnerProperties properties = context.getBean(RunnerProperties.class);
          assertThat(properties.retentionRunHistoryMaxAge()).isEqualTo(Duration.ofDays(30));
          assertThat(properties.retentionRunHistoryMaxCount()).isEqualTo(500);
          assertThat(properties.retentionArtifactMaxAge()).isEqualTo(Duration.ofDays(14));
          assertThat(properties.retentionCleanupInterval()).isEqualTo(Duration.ofHours(1));
          assertThat(properties.retentionRateLimit())
              .isEqualTo(new RateLimitRule(10, Duration.ofHours(1)));
        });
  }

  @Test
  void bindsTheRealApplicationYmlDiskProtectionKeysCorrectly() {
    contextRunner.run(
        (context) -> {
          RunnerProperties properties = context.getBean(RunnerProperties.class);
          assertThat(properties.diskMinFreeBytes()).isEqualTo(1_073_741_824L);
          assertThat(properties.artifactMaxBytes()).isEqualTo(26_214_400L);
          assertThat(properties.runMaxTotalArtifactBytes()).isEqualTo(209_715_200L);
          assertThat(properties.manifestMaxBytes()).isEqualTo(2_097_152L);
          assertThat(properties.rawEventMaxBytes()).isEqualTo(2_097_152L);
          assertThat(properties.managedScratchMaxBytes()).isEqualTo(104_857_600L);
          assertThat(properties.diskUsageRateLimit())
              .isEqualTo(new RateLimitRule(10, Duration.ofHours(1)));
          assertThat(properties.runMaxDiskBytes())
              .isEqualTo(
                  209_715_200L
                      + properties.processLogMaxBytes()
                      + 2_097_152L
                      + 2_097_152L
                      + 104_857_600L);
        });
  }

  private RunnerProperties valid(
      RateLimitRule oauthAuthorizationRateLimit,
      RateLimitRule oauthCallbackRateLimit,
      RateLimitRule createRunRateLimitPerMinute,
      RateLimitRule createRunRateLimitPerHour,
      RateLimitRule cancelRunRateLimit,
      RateLimitRule publicReadRateLimit,
      RateLimitRule downloadRateLimit,
      int sseMaxConnectionsPerIp,
      long maxRequestBodyBytes) {
    return validWithRetention(
        oauthAuthorizationRateLimit,
        oauthCallbackRateLimit,
        createRunRateLimitPerMinute,
        createRunRateLimitPerHour,
        cancelRunRateLimit,
        publicReadRateLimit,
        downloadRateLimit,
        sseMaxConnectionsPerIp,
        maxRequestBodyBytes,
        Duration.ofDays(30),
        500,
        Duration.ofDays(14),
        Duration.ofHours(1));
  }

  private RunnerProperties validWithRetention(
      RateLimitRule oauthAuthorizationRateLimit,
      RateLimitRule oauthCallbackRateLimit,
      RateLimitRule createRunRateLimitPerMinute,
      RateLimitRule createRunRateLimitPerHour,
      RateLimitRule cancelRunRateLimit,
      RateLimitRule publicReadRateLimit,
      RateLimitRule downloadRateLimit,
      int sseMaxConnectionsPerIp,
      long maxRequestBodyBytes,
      Duration retentionRunHistoryMaxAge,
      int retentionRunHistoryMaxCount,
      Duration retentionArtifactMaxAge,
      Duration retentionCleanupInterval) {
    return validWithRetentionRateLimit(
        oauthAuthorizationRateLimit,
        oauthCallbackRateLimit,
        createRunRateLimitPerMinute,
        createRunRateLimitPerHour,
        cancelRunRateLimit,
        publicReadRateLimit,
        downloadRateLimit,
        sseMaxConnectionsPerIp,
        maxRequestBodyBytes,
        retentionRunHistoryMaxAge,
        retentionRunHistoryMaxCount,
        retentionArtifactMaxAge,
        retentionCleanupInterval,
        A_RULE);
  }

  private RunnerProperties validWithRetentionRateLimit(
      RateLimitRule oauthAuthorizationRateLimit,
      RateLimitRule oauthCallbackRateLimit,
      RateLimitRule createRunRateLimitPerMinute,
      RateLimitRule createRunRateLimitPerHour,
      RateLimitRule cancelRunRateLimit,
      RateLimitRule publicReadRateLimit,
      RateLimitRule downloadRateLimit,
      int sseMaxConnectionsPerIp,
      long maxRequestBodyBytes,
      Duration retentionRunHistoryMaxAge,
      int retentionRunHistoryMaxCount,
      Duration retentionArtifactMaxAge,
      Duration retentionCleanupInterval,
      RateLimitRule retentionRateLimit) {
    return validWithDisk(
        oauthAuthorizationRateLimit,
        oauthCallbackRateLimit,
        createRunRateLimitPerMinute,
        createRunRateLimitPerHour,
        cancelRunRateLimit,
        publicReadRateLimit,
        downloadRateLimit,
        sseMaxConnectionsPerIp,
        maxRequestBodyBytes,
        retentionRunHistoryMaxAge,
        retentionRunHistoryMaxCount,
        retentionArtifactMaxAge,
        retentionCleanupInterval,
        retentionRateLimit,
        1_048_576L,
        26_214_400L,
        209_715_200L,
        2_097_152L,
        2_097_152L,
        104_857_600L,
        A_RULE,
        Duration.ofSeconds(60));
  }

  private RunnerProperties validWithDisk(
      RateLimitRule oauthAuthorizationRateLimit,
      RateLimitRule oauthCallbackRateLimit,
      RateLimitRule createRunRateLimitPerMinute,
      RateLimitRule createRunRateLimitPerHour,
      RateLimitRule cancelRunRateLimit,
      RateLimitRule publicReadRateLimit,
      RateLimitRule downloadRateLimit,
      int sseMaxConnectionsPerIp,
      long maxRequestBodyBytes,
      Duration retentionRunHistoryMaxAge,
      int retentionRunHistoryMaxCount,
      Duration retentionArtifactMaxAge,
      Duration retentionCleanupInterval,
      RateLimitRule retentionRateLimit,
      long diskMinFreeBytes,
      long artifactMaxBytes,
      long runMaxTotalArtifactBytes,
      long manifestMaxBytes,
      long rawEventMaxBytes,
      long managedScratchMaxBytes,
      RateLimitRule diskUsageRateLimit,
      Duration metricsSampleInterval) {
    return new RunnerProperties(
        ".",
        Duration.ofMinutes(10),
        "build/runner-events/raw",
        "build/runner-logs",
        "src/test/resources/catalog/public-test-catalog.json",
        "build/runner-artifacts",
        1024 * 1024,
        Duration.ofSeconds(5),
        Duration.ofSeconds(2),
        5,
        Duration.ofMillis(150),
        Duration.ofSeconds(5),
        100,
        Duration.ofSeconds(15),
        Duration.ofMinutes(10),
        oauthAuthorizationRateLimit,
        oauthCallbackRateLimit,
        createRunRateLimitPerMinute,
        createRunRateLimitPerHour,
        cancelRunRateLimit,
        publicReadRateLimit,
        downloadRateLimit,
        sseMaxConnectionsPerIp,
        maxRequestBodyBytes,
        retentionRunHistoryMaxAge,
        retentionRunHistoryMaxCount,
        retentionArtifactMaxAge,
        retentionCleanupInterval,
        retentionRateLimit,
        diskMinFreeBytes,
        artifactMaxBytes,
        runMaxTotalArtifactBytes,
        manifestMaxBytes,
        rawEventMaxBytes,
        managedScratchMaxBytes,
        diskUsageRateLimit,
        metricsSampleInterval);
  }

  private static final RateLimitRule A_RULE = new RateLimitRule(5, Duration.ofMinutes(1));

  @Test
  void rejectsANullOauthAuthorizationRateLimit() {
    assertThatThrownBy(() -> valid(null, A_RULE, A_RULE, A_RULE, A_RULE, A_RULE, A_RULE, 3, 16384))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("oauth-authorization-rate-limit");
  }

  @Test
  void rejectsANullOauthCallbackRateLimit() {
    assertThatThrownBy(() -> valid(A_RULE, null, A_RULE, A_RULE, A_RULE, A_RULE, A_RULE, 3, 16384))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("oauth-callback-rate-limit");
  }

  @Test
  void rejectsANullCreateRunRateLimitPerMinute() {
    assertThatThrownBy(() -> valid(A_RULE, A_RULE, null, A_RULE, A_RULE, A_RULE, A_RULE, 3, 16384))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("create-run-rate-limit-per-minute");
  }

  @Test
  void rejectsANullCreateRunRateLimitPerHour() {
    assertThatThrownBy(() -> valid(A_RULE, A_RULE, A_RULE, null, A_RULE, A_RULE, A_RULE, 3, 16384))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("create-run-rate-limit-per-hour");
  }

  @Test
  void rejectsANullCancelRunRateLimit() {
    assertThatThrownBy(() -> valid(A_RULE, A_RULE, A_RULE, A_RULE, null, A_RULE, A_RULE, 3, 16384))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cancel-run-rate-limit");
  }

  @Test
  void rejectsANullPublicReadRateLimit() {
    assertThatThrownBy(() -> valid(A_RULE, A_RULE, A_RULE, A_RULE, A_RULE, null, A_RULE, 3, 16384))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("public-read-rate-limit");
  }

  @Test
  void rejectsANullDownloadRateLimit() {
    assertThatThrownBy(() -> valid(A_RULE, A_RULE, A_RULE, A_RULE, A_RULE, A_RULE, null, 3, 16384))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("download-rate-limit");
  }

  @Test
  void rejectsAZeroSseMaxConnectionsPerIp() {
    assertThatThrownBy(
            () -> valid(A_RULE, A_RULE, A_RULE, A_RULE, A_RULE, A_RULE, A_RULE, 0, 16384))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sse-max-connections-per-ip");
  }

  @Test
  void rejectsAMaxRequestBodyBytesBelow1024() {
    assertThatThrownBy(() -> valid(A_RULE, A_RULE, A_RULE, A_RULE, A_RULE, A_RULE, A_RULE, 3, 1023))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("max-request-body-bytes");
  }

  @Test
  void rejectsARateLimitRuleWithZeroMaxAttempts() {
    assertThatThrownBy(() -> new RateLimitRule(0, Duration.ofMinutes(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("max-attempts");
  }

  @Test
  void rejectsARateLimitRuleWithANonPositiveWindow() {
    assertThatThrownBy(() -> new RateLimitRule(5, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("window");
  }

  @Test
  void rejectsANonPositiveRetentionRunHistoryMaxAge() {
    assertThatThrownBy(
            () ->
                validWithRetention(
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    3,
                    16384,
                    Duration.ZERO,
                    500,
                    Duration.ofDays(14),
                    Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retention-run-history-max-age");
  }

  @Test
  void rejectsAZeroRetentionRunHistoryMaxCount() {
    assertThatThrownBy(
            () ->
                validWithRetention(
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    3,
                    16384,
                    Duration.ofDays(30),
                    0,
                    Duration.ofDays(14),
                    Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retention-run-history-max-count");
  }

  @Test
  void rejectsANonPositiveRetentionArtifactMaxAge() {
    assertThatThrownBy(
            () ->
                validWithRetention(
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    3,
                    16384,
                    Duration.ofDays(30),
                    500,
                    Duration.ZERO,
                    Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retention-artifact-max-age");
  }

  @Test
  void rejectsANonPositiveRetentionCleanupInterval() {
    assertThatThrownBy(
            () ->
                validWithRetention(
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    3,
                    16384,
                    Duration.ofDays(30),
                    500,
                    Duration.ofDays(14),
                    Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retention-cleanup-interval");
  }

  @Test
  void rejectsARetentionArtifactMaxAgeLongerThanRunHistoryMaxAge() {
    assertThatThrownBy(
            () ->
                validWithRetention(
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    3,
                    16384,
                    Duration.ofDays(30),
                    500,
                    Duration.ofDays(31),
                    Duration.ofHours(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retention-artifact-max-age")
        .hasMessageContaining("retention-run-history-max-age");
  }

  @Test
  void rejectsANullRetentionRateLimit() {
    assertThatThrownBy(
            () ->
                validWithRetentionRateLimit(
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    A_RULE,
                    3,
                    16384,
                    Duration.ofDays(30),
                    500,
                    Duration.ofDays(14),
                    Duration.ofHours(1),
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retention-rate-limit");
  }

  private RunnerProperties validDisk(
      long diskMinFreeBytes,
      long artifactMaxBytes,
      long runMaxTotalArtifactBytes,
      long manifestMaxBytes,
      long rawEventMaxBytes,
      long managedScratchMaxBytes,
      RateLimitRule diskUsageRateLimit) {
    return validWithDisk(
        A_RULE,
        A_RULE,
        A_RULE,
        A_RULE,
        A_RULE,
        A_RULE,
        A_RULE,
        3,
        16384,
        Duration.ofDays(30),
        500,
        Duration.ofDays(14),
        Duration.ofHours(1),
        A_RULE,
        diskMinFreeBytes,
        artifactMaxBytes,
        runMaxTotalArtifactBytes,
        manifestMaxBytes,
        rawEventMaxBytes,
        managedScratchMaxBytes,
        diskUsageRateLimit,
        Duration.ofSeconds(60));
  }

  @Test
  void rejectsADiskMinFreeBytesBelow1MiB() {
    assertThatThrownBy(
            () ->
                validDisk(
                    1_048_575L,
                    26_214_400L,
                    209_715_200L,
                    2_097_152L,
                    2_097_152L,
                    104_857_600L,
                    A_RULE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("disk-min-free-bytes");
  }

  @Test
  void rejectsAnArtifactMaxBytesBelow1024() {
    assertThatThrownBy(
            () ->
                validDisk(
                    1_048_576L, 1023L, 209_715_200L, 2_097_152L, 2_097_152L, 104_857_600L, A_RULE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("artifact-max-bytes");
  }

  @Test
  void rejectsARunMaxTotalArtifactBytesBelow1024() {
    assertThatThrownBy(
            () -> validDisk(1_048_576L, 1024L, 1023L, 2_097_152L, 2_097_152L, 104_857_600L, A_RULE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("run-max-total-artifact-bytes");
  }

  @Test
  void rejectsAnArtifactMaxBytesExceedingRunMaxTotalArtifactBytes() {
    assertThatThrownBy(
            () ->
                validDisk(
                    1_048_576L,
                    209_715_201L,
                    209_715_200L,
                    2_097_152L,
                    2_097_152L,
                    104_857_600L,
                    A_RULE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("artifact-max-bytes")
        .hasMessageContaining("run-max-total-artifact-bytes");
  }

  @Test
  void rejectsAManifestMaxBytesBelow1024() {
    assertThatThrownBy(
            () ->
                validDisk(
                    1_048_576L, 26_214_400L, 209_715_200L, 1023L, 2_097_152L, 104_857_600L, A_RULE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("manifest-max-bytes");
  }

  @Test
  void rejectsAManifestMaxBytesAbove100MiB() {
    assertThatThrownBy(
            () ->
                validDisk(
                    1_048_576L,
                    26_214_400L,
                    209_715_200L,
                    104_857_601L,
                    2_097_152L,
                    104_857_600L,
                    A_RULE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("manifest-max-bytes");
  }

  @Test
  void rejectsARawEventMaxBytesBelow1024() {
    assertThatThrownBy(
            () ->
                validDisk(
                    1_048_576L, 26_214_400L, 209_715_200L, 2_097_152L, 1023L, 104_857_600L, A_RULE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("raw-event-max-bytes");
  }

  @Test
  void rejectsAManagedScratchMaxBytesBelow1024() {
    assertThatThrownBy(
            () ->
                validDisk(
                    1_048_576L, 26_214_400L, 209_715_200L, 2_097_152L, 2_097_152L, 1023L, A_RULE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("managed-scratch-max-bytes");
  }

  @Test
  void rejectsANullDiskUsageRateLimit() {
    assertThatThrownBy(
            () ->
                validDisk(
                    1_048_576L,
                    26_214_400L,
                    209_715_200L,
                    2_097_152L,
                    2_097_152L,
                    104_857_600L,
                    null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("disk-usage-rate-limit");
  }

  @Test
  void rejectsADiskBudgetSumThatOverflowsALong() {
    assertThatThrownBy(
            () ->
                validDisk(
                    Long.MAX_VALUE - 1024,
                    26_214_400L,
                    Long.MAX_VALUE - 2048,
                    2_097_152L,
                    2_097_152L,
                    104_857_600L,
                    A_RULE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("overflows a long");
  }

  @Test
  void bindsTheRealApplicationYmlMetricsSampleIntervalCorrectly() {
    contextRunner.run(
        (context) -> {
          RunnerProperties properties = context.getBean(RunnerProperties.class);
          assertThat(properties.metricsSampleInterval()).isEqualTo(Duration.ofSeconds(60));
        });
  }

  private RunnerProperties validMetrics(Duration metricsSampleInterval) {
    return validWithDisk(
        A_RULE,
        A_RULE,
        A_RULE,
        A_RULE,
        A_RULE,
        A_RULE,
        A_RULE,
        3,
        16384,
        Duration.ofDays(30),
        500,
        Duration.ofDays(14),
        Duration.ofHours(1),
        A_RULE,
        1_048_576L,
        26_214_400L,
        209_715_200L,
        2_097_152L,
        2_097_152L,
        104_857_600L,
        A_RULE,
        metricsSampleInterval);
  }

  @Test
  void rejectsAZeroMetricsSampleInterval() {
    assertThatThrownBy(() -> validMetrics(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("metrics-sample-interval");
  }

  @Test
  void rejectsANegativeMetricsSampleInterval() {
    assertThatThrownBy(() -> validMetrics(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("metrics-sample-interval");
  }

  @Test
  void rejectsANullMetricsSampleInterval() {
    assertThatThrownBy(() -> validMetrics(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("metrics-sample-interval");
  }

  /**
   * A sub-millisecond {@code Duration} truncates to 0ms via {@code DiskMetricsSampler#toMillis()},
   * which {@code scheduleWithFixedDelay} rejects outright - validation must catch this with a clear
   * property-named error, not a confusing failure deep inside {@code java.util.concurrent} during
   * bean creation.
   */
  @Test
  void rejectsASubMillisecondMetricsSampleInterval() {
    assertThatThrownBy(() -> validMetrics(Duration.ofNanos(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("metrics-sample-interval");
  }
}
