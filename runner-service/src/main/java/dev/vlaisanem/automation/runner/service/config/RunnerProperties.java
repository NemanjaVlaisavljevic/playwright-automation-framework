package dev.vlaisanem.automation.runner.service.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param repoRoot directory containing the gradlew wrapper this service invokes; defaults to {@code
 *     .} (the service's own working directory).
 * @param processTimeout hard deadline after which a run's Gradle process is forcibly killed.
 * @param rawEventsDir directory runner-listener writes each run's raw {@code <runId>.tests.jsonl}/
 *     {@code .tests.complete} marker files into - must match {@code -Drunner.rawEventsDir}. The
 *     canonical, cross-run-lifecycle event timeline lives in Postgres, not on disk.
 * @param logsDir directory containing one bounded combined stdout/stderr log per run.
 * @param testCatalogPath path (relative to {@link #repoRoot}) of the committed, JUnit-discovery-
 *     generated {@code CUSTOM}-suite test catalog.
 * @param artifactsDir root directory under which every run gets its own isolated subdirectory
 *     (named after its runId), passed to the spawned Gradle process as {@code ARTIFACTS_DIR}.
 * @param processLogMaxBytes maximum number of bytes retained in one process log.
 * @param terminationGracePeriod time allowed for graceful and then forced process-tree shutdown.
 * @param degradedPollInterval how often the background reaper re-checks a known-surviving process
 *     tree while the runner is refusing new submissions.
 * @param queueCapacity maximum number of runs allowed to wait behind the one currently executing.
 * @param ingestionPollInterval how often {@code ListenerEventIngestor} re-checks the raw event file
 *     for new bytes while a run's process is still active.
 * @param ingestionDrainTimeout upper bound on how long a run's finalization waits for the ingestor
 *     to notice a stop signal and finish; a safety bound against a stuck ingestion thread, not
 *     something normal completion is expected to hit.
 * @param sseMaxSubscribers maximum number of concurrent SSE event-stream subscribers the hub
 *     accepts at once - each holds its own dedicated delivery thread, so this bounds thread usage.
 * @param sseHeartbeatInterval how often a keep-alive comment is sent on an idle SSE connection, so
 *     intermediary proxies/load balancers don't time it out as inactive.
 * @param sseEmitterTimeout hard upper bound on how long one SSE connection stays open before the
 *     server completes it; expected recovery is a client reconnect with {@code Last-Event-ID}.
 * @param oauthAuthorizationRateLimit per-client-IP limit on {@code GET
 *     /api/v1/auth/oauth2/authorization/github}, the nearly-free redirect-starter.
 * @param oauthCallbackRateLimit per-client-IP limit on {@code GET
 *     /api/v1/auth/oauth2/callback/github}, the login-flow request that spends a real GitHub API
 *     call.
 * @param createRunRateLimitPerMinute per-admin (GitHub numeric id) short-window limit on {@code
 *     POST /api/v1/runs}.
 * @param createRunRateLimitPerHour per-admin longer-window limit on the same endpoint, enforced
 *     independently of and in addition to {@link #createRunRateLimitPerMinute} - both must pass.
 * @param cancelRunRateLimit per-admin limit on {@code POST /api/v1/runs/*&#47;cancel}, tracked
 *     separately from run creation.
 * @param publicReadRateLimit per-client-IP limit on the anonymous, unauthenticated read-only GET
 *     routes (run list/detail, capabilities, test catalog).
 * @param downloadRateLimit per-client-IP limit on process-log/artifact download routes, tracked
 *     separately from the cheaper plain-JSON reads above.
 * @param sseMaxConnectionsPerIp maximum number of concurrent SSE subscriptions one client IP may
 *     hold, enforced in addition to {@link #sseMaxSubscribers}'s global ceiling.
 * @param maxRequestBodyBytes hard cap on request body size, enforced before any JSON
 *     deserialization is attempted.
 * @param retentionRunHistoryMaxAge a terminal run is eligible for full cleanup once its {@code
 *     finished_at} is older than this - an either-bound trigger together with {@link
 *     #retentionRunHistoryMaxCount}, never both required at once.
 * @param retentionRunHistoryMaxCount a terminal run is eligible for full cleanup once its rank
 *     (newest-first, ties broken by {@code requested_at} then {@code run_id}) among terminal,
 *     not-yet-cleaned-up runs exceeds this count.
 * @param retentionArtifactMaxAge a terminal run's artifact files are purged once its {@code
 *     finished_at} is older than this; must be no larger than {@link #retentionRunHistoryMaxAge}
 *     (validated below), otherwise the purge branch could never fire before full-run cleanup.
 * @param retentionCleanupInterval how often the background retention sweep runs.
 * @param retentionRateLimit per-admin limit on both {@code GET /api/v1/retention/preview} and
 *     {@code POST /api/v1/retention/run}, tracked as two independent counters against this
 *     threshold - deliberately conservative since a real sweep does real DB/filesystem work.
 * @param diskMinFreeBytes the floor that must remain free even after a newly-starting run consumes
 *     up to {@link #runMaxDiskBytes()} more.
 * @param artifactMaxBytes maximum size of a single artifact file, enforced primarily at the
 *     producer and re-checked here as a second, independent layer.
 * @param runMaxTotalArtifactBytes maximum total artifact bytes one run may accumulate, computed
 *     from the real artifacts directory's contents rather than an in-memory counter, since two
 *     independent OS processes can write to it.
 * @param manifestMaxBytes maximum size of one run's {@code manifest.jsonl}, enforced both at the
 *     producer and the consumer's bounded read, which allocates a single buffer this large.
 * @param rawEventMaxBytes maximum size of one run's raw {@code <runId>.tests.jsonl} event stream -
 *     writing stops on the first breach with a distinct {@code .tests.overflow} marker, so a
 *     truncated stream is never mistaken for a cleanly complete one.
 * @param managedScratchMaxBytes a disk-budget reservation for Gradle/JUnit report and temporary
 *     output this design does not itself enforce, folded into {@link #runMaxDiskBytes()} so the
 *     availability guard doesn't undercount a run's real footprint.
 * @param diskUsageRateLimit per-admin limit on {@code GET /api/v1/disk/usage}, real work (a
 *     filesystem-tree walk plus a live Postgres size query).
 * @param metricsSampleInterval how often {@code DiskMetricsSampler} recomputes and caches disk
 *     usage in the background, so it need not run on every Prometheus scrape.
 */
@ConfigurationProperties(prefix = "runner")
public record RunnerProperties(
    String repoRoot,
    Duration processTimeout,
    String rawEventsDir,
    String logsDir,
    String testCatalogPath,
    String artifactsDir,
    long processLogMaxBytes,
    Duration terminationGracePeriod,
    Duration degradedPollInterval,
    int queueCapacity,
    Duration ingestionPollInterval,
    Duration ingestionDrainTimeout,
    int sseMaxSubscribers,
    Duration sseHeartbeatInterval,
    Duration sseEmitterTimeout,
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

  private static final long MANIFEST_MAX_BYTES_CEILING = 104_857_600L; // 100 MiB

  /**
   * Derived, never independently configured: the worst-case total disk one starting run can still
   * consume across every writer it touches - see {@link #diskMinFreeBytes} for how the
   * submit/pre-launch guards use this.
   */
  public long runMaxDiskBytes() {
    try {
      long total = runMaxTotalArtifactBytes;
      total = Math.addExact(total, processLogMaxBytes);
      total = Math.addExact(total, rawEventMaxBytes);
      total = Math.addExact(total, manifestMaxBytes);
      total = Math.addExact(total, managedScratchMaxBytes);
      return total;
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException(
          "runner.run-max-total-artifact-bytes + process-log-max-bytes + raw-event-max-bytes +"
              + " manifest-max-bytes + managed-scratch-max-bytes overflows a long",
          overflow);
    }
  }

  public RunnerProperties {
    if (repoRoot == null || repoRoot.isBlank()) {
      throw new IllegalArgumentException("runner.repo-root must not be blank");
    }
    if (processTimeout == null || processTimeout.isZero() || processTimeout.isNegative()) {
      throw new IllegalArgumentException("runner.process-timeout must be positive");
    }
    if (rawEventsDir == null || rawEventsDir.isBlank()) {
      throw new IllegalArgumentException("runner.raw-events-dir must not be blank");
    }
    if (logsDir == null || logsDir.isBlank()) {
      throw new IllegalArgumentException("runner.logs-dir must not be blank");
    }
    if (testCatalogPath == null || testCatalogPath.isBlank()) {
      throw new IllegalArgumentException("runner.test-catalog-path must not be blank");
    }
    if (artifactsDir == null || artifactsDir.isBlank()) {
      throw new IllegalArgumentException("runner.artifacts-dir must not be blank");
    }
    if (processLogMaxBytes < 1024) {
      throw new IllegalArgumentException("runner.process-log-max-bytes must be at least 1024");
    }
    if (terminationGracePeriod == null
        || terminationGracePeriod.isZero()
        || terminationGracePeriod.isNegative()) {
      throw new IllegalArgumentException("runner.termination-grace-period must be positive");
    }
    if (degradedPollInterval == null
        || degradedPollInterval.isZero()
        || degradedPollInterval.isNegative()) {
      throw new IllegalArgumentException("runner.degraded-poll-interval must be positive");
    }
    if (queueCapacity < 1) {
      throw new IllegalArgumentException("runner.queue-capacity must be at least 1");
    }
    if (ingestionPollInterval == null
        || ingestionPollInterval.isZero()
        || ingestionPollInterval.isNegative()) {
      throw new IllegalArgumentException("runner.ingestion-poll-interval must be positive");
    }
    if (ingestionDrainTimeout == null
        || ingestionDrainTimeout.isZero()
        || ingestionDrainTimeout.isNegative()) {
      throw new IllegalArgumentException("runner.ingestion-drain-timeout must be positive");
    }
    if (sseMaxSubscribers < 1) {
      throw new IllegalArgumentException("runner.sse-max-subscribers must be at least 1");
    }
    if (sseHeartbeatInterval == null
        || sseHeartbeatInterval.isZero()
        || sseHeartbeatInterval.isNegative()) {
      throw new IllegalArgumentException("runner.sse-heartbeat-interval must be positive");
    }
    if (sseEmitterTimeout == null || sseEmitterTimeout.isZero() || sseEmitterTimeout.isNegative()) {
      throw new IllegalArgumentException("runner.sse-emitter-timeout must be positive");
    }
    if (oauthAuthorizationRateLimit == null) {
      throw new IllegalArgumentException("runner.oauth-authorization-rate-limit must be set");
    }
    if (oauthCallbackRateLimit == null) {
      throw new IllegalArgumentException("runner.oauth-callback-rate-limit must be set");
    }
    if (createRunRateLimitPerMinute == null) {
      throw new IllegalArgumentException("runner.create-run-rate-limit-per-minute must be set");
    }
    if (createRunRateLimitPerHour == null) {
      throw new IllegalArgumentException("runner.create-run-rate-limit-per-hour must be set");
    }
    if (cancelRunRateLimit == null) {
      throw new IllegalArgumentException("runner.cancel-run-rate-limit must be set");
    }
    if (publicReadRateLimit == null) {
      throw new IllegalArgumentException("runner.public-read-rate-limit must be set");
    }
    if (downloadRateLimit == null) {
      throw new IllegalArgumentException("runner.download-rate-limit must be set");
    }
    if (sseMaxConnectionsPerIp < 1) {
      throw new IllegalArgumentException("runner.sse-max-connections-per-ip must be at least 1");
    }
    if (maxRequestBodyBytes < 1024) {
      throw new IllegalArgumentException("runner.max-request-body-bytes must be at least 1024");
    }
    if (retentionRunHistoryMaxAge == null
        || retentionRunHistoryMaxAge.isZero()
        || retentionRunHistoryMaxAge.isNegative()) {
      throw new IllegalArgumentException("runner.retention-run-history-max-age must be positive");
    }
    if (retentionRunHistoryMaxCount < 1) {
      throw new IllegalArgumentException(
          "runner.retention-run-history-max-count must be at least 1");
    }
    if (retentionArtifactMaxAge == null
        || retentionArtifactMaxAge.isZero()
        || retentionArtifactMaxAge.isNegative()) {
      throw new IllegalArgumentException("runner.retention-artifact-max-age must be positive");
    }
    if (retentionCleanupInterval == null
        || retentionCleanupInterval.isZero()
        || retentionCleanupInterval.isNegative()) {
      throw new IllegalArgumentException("runner.retention-cleanup-interval must be positive");
    }
    if (retentionArtifactMaxAge.compareTo(retentionRunHistoryMaxAge) > 0) {
      throw new IllegalArgumentException(
          "runner.retention-artifact-max-age must not exceed runner.retention-run-history-max-age"
              + " - a longer artifact window could never fire before full-run cleanup already"
              + " deleted the run");
    }
    if (retentionRateLimit == null) {
      throw new IllegalArgumentException("runner.retention-rate-limit must be set");
    }
    if (diskMinFreeBytes < 1_048_576) {
      throw new IllegalArgumentException("runner.disk-min-free-bytes must be at least 1048576");
    }
    if (artifactMaxBytes < 1024) {
      throw new IllegalArgumentException("runner.artifact-max-bytes must be at least 1024");
    }
    if (runMaxTotalArtifactBytes < 1024) {
      throw new IllegalArgumentException(
          "runner.run-max-total-artifact-bytes must be at least 1024");
    }
    if (artifactMaxBytes > runMaxTotalArtifactBytes) {
      throw new IllegalArgumentException(
          "runner.artifact-max-bytes must not exceed runner.run-max-total-artifact-bytes - a"
              + " single file can never legitimately exceed the whole run's own budget");
    }
    if (manifestMaxBytes < 1024) {
      throw new IllegalArgumentException("runner.manifest-max-bytes must be at least 1024");
    }
    if (manifestMaxBytes > MANIFEST_MAX_BYTES_CEILING) {
      throw new IllegalArgumentException(
          "runner.manifest-max-bytes must not exceed "
              + MANIFEST_MAX_BYTES_CEILING
              + " - ArtifactManifestReader allocates a single in-memory buffer this large");
    }
    if (rawEventMaxBytes < 1024) {
      throw new IllegalArgumentException("runner.raw-event-max-bytes must be at least 1024");
    }
    if (managedScratchMaxBytes < 1024) {
      throw new IllegalArgumentException("runner.managed-scratch-max-bytes must be at least 1024");
    }
    if (diskUsageRateLimit == null) {
      throw new IllegalArgumentException("runner.disk-usage-rate-limit must be set");
    }
    if (metricsSampleInterval == null
        || metricsSampleInterval.isZero()
        || metricsSampleInterval.isNegative()) {
      throw new IllegalArgumentException("runner.metrics-sample-interval must be positive");
    }
    // A merely-positive sub-millisecond value would pass the check above yet truncate to 0 via
    // toMillis(), which scheduleWithFixedDelay then rejects - fail here with a clear, property-
    // named error instead.
    if (metricsSampleInterval.toMillis() < 1) {
      throw new IllegalArgumentException(
          "runner.metrics-sample-interval must be at least 1ms once rounded down, was: "
              + metricsSampleInterval);
    }
    try {
      long runMaxDiskBytes = runMaxTotalArtifactBytes;
      runMaxDiskBytes = Math.addExact(runMaxDiskBytes, processLogMaxBytes);
      runMaxDiskBytes = Math.addExact(runMaxDiskBytes, rawEventMaxBytes);
      runMaxDiskBytes = Math.addExact(runMaxDiskBytes, manifestMaxBytes);
      runMaxDiskBytes = Math.addExact(runMaxDiskBytes, managedScratchMaxBytes);
      Math.addExact(diskMinFreeBytes, runMaxDiskBytes);
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException(
          "runner.disk-min-free-bytes plus the run-max-total-artifact-bytes/process-log-max-bytes/"
              + "raw-event-max-bytes/manifest-max-bytes/managed-scratch-max-bytes sum overflows a"
              + " long",
          overflow);
    }
  }
}
