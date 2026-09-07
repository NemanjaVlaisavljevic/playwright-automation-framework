package dev.vlaisanem.automation.runner.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Real {@code @ConfigurationProperties} binding against the actual {@code application.yml} on the
 * classpath - not merely constructing the record directly - proves the D3.3 rate-limit keys
 * (including the nested {@link RateLimitRule} values) and the D4.1 retention keys bind the way the
 * rest of this codebase assumes, the same "verify the binding actually works" discipline {@code
 * RunnerSecurityEnvironmentPostProcessor}'s {@code Binder}-based fix already established. Also
 * covers the compact constructor's own validation boundaries for the newly-added D3.3/D4.1 fields
 * specifically - every pre-existing field's validation is already exercised indirectly by every
 * other test in this module constructing a valid {@link RunnerProperties}.
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
        retentionRateLimit);
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
}
