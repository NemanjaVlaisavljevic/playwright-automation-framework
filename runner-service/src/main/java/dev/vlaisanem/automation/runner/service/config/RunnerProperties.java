package dev.vlaisanem.automation.runner.service.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param repoRoot directory containing the gradlew wrapper this service invokes. Defaults to {@code
 *     .} - the service is expected to be launched with the repository root as its working
 *     directory; that assumption is documented here, not silently assumed elsewhere.
 * @param processTimeout hard deadline after which a run's Gradle process is forcibly killed.
 * @param rawEventsDir directory runner-listener writes each run's raw {@code <runId>.tests.jsonl}/
 *     {@code .tests.complete} marker files into - must match what gets passed as {@code
 *     -Drunner.rawEventsDir}. This is the listener's own, unprocessed test-event stream; the runner
 *     service's own canonical, cross-run-lifecycle event timeline lives in Postgres (see {@code
 *     RunLifecycleStore}), not on disk.
 * @param logsDir directory containing one bounded combined stdout/stderr log per run.
 * @param testCatalogPath path (relative to {@link #repoRoot}) of the committed, JUnit-discovery-
 *     generated {@code CUSTOM}-suite test catalog - see {@code TestCatalogGenerator} in the main
 *     suite's own {@code tooling} package for how it is produced, and {@code
 *     testCatalogGenerate}/{@code testCatalogCheck} in the root {@code build.gradle} for how drift
 *     from it is caught in CI.
 * @param artifactsDir root directory under which every run gets its own isolated subdirectory
 *     (named after its runId), passed to the spawned Gradle process as the {@code ARTIFACTS_DIR}
 *     environment variable - the same configuration key {@code TestConfig#artifactsDirectory()}
 *     already reads. Keeps screenshots/traces from two different runs (sequential or, once
 *     supported, concurrent) from ever landing in the same directory.
 * @param processLogMaxBytes maximum number of bytes retained in one process log.
 * @param terminationGracePeriod time allowed for graceful and then forced process-tree shutdown.
 * @param degradedPollInterval how often the background reaper re-checks a known-surviving process
 *     tree while the runner is refusing new submissions.
 * @param queueCapacity maximum number of runs allowed to wait behind the one currently executing.
 * @param ingestionPollInterval how often {@code ListenerEventIngestor} re-checks the raw event file
 *     for new bytes while a run's process is still active.
 * @param ingestionDrainTimeout upper bound on how long a run's finalization waits for the ingestor
 *     to notice a stop signal and finish - the ingestor's own responsiveness to that signal (at
 *     most one {@link #ingestionPollInterval}) is what actually governs the common case; this is a
 *     safety bound against a stuck ingestion thread, not something normal completion is expected to
 *     hit.
 * @param sseMaxSubscribers maximum number of concurrent SSE event-stream subscribers the hub will
 *     accept at once - each one holds its own dedicated delivery thread for the life of the
 *     connection, so this is the bound on that thread usage, not merely a request-rate limit.
 * @param sseHeartbeatInterval how often a keep-alive comment is sent on an idle SSE connection, so
 *     intermediary proxies/load balancers do not time it out as inactive.
 * @param sseEmitterTimeout hard upper bound on how long one SSE connection is kept open before the
 *     server itself completes it, independent of client behavior - the expected recovery is a
 *     client reconnect with {@code Last-Event-ID}.
 * @param oauthAuthorizationRateLimit (D3.3) per-client-IP limit on {@code GET
 *     /api/v1/auth/oauth2/authorization/github} - the redirect-starter, nearly free to call, so a
 *     looser limit than the callback below.
 * @param oauthCallbackRateLimit (D3.3) per-client-IP limit on {@code GET
 *     /api/v1/auth/oauth2/callback/github} - the one login-flow request that actually spends a real
 *     GitHub API call (the authorization-code exchange), so this is the login surface that matters
 *     most to protect.
 * @param createRunRateLimitPerMinute (D3.3) per-admin (GitHub numeric id) short-window limit on
 *     {@code POST /api/v1/runs}.
 * @param createRunRateLimitPerHour (D3.3) per-admin longer-window limit on the same endpoint,
 *     enforced independently of and in addition to {@link #createRunRateLimitPerMinute} - both must
 *     pass.
 * @param cancelRunRateLimit (D3.3) per-admin limit on {@code POST /api/v1/runs/*&#47;cancel} -
 *     tracked separately from run creation, never assumed to be equally expensive.
 * @param publicReadRateLimit (D3.3) per-client-IP limit on the anonymous, unauthenticated read-only
 *     GET routes (run list/detail, capabilities, test catalog) - the largest anonymous surface,
 *     needing no login at all.
 * @param downloadRateLimit (D3.3) per-client-IP limit on process-log/artifact download routes,
 *     tracked separately from the cheaper plain-JSON reads above.
 * @param sseMaxConnectionsPerIp (D3.3) maximum number of concurrent SSE subscriptions one client IP
 *     may hold at once - enforced in addition to, never instead of, {@link #sseMaxSubscribers}'s
 *     existing global ceiling; without this, one client alone could occupy every global slot.
 * @param maxRequestBodyBytes (D3.3) hard cap on request body size, enforced before any JSON
 *     deserialization is attempted - a real {@code CreateRunRequest} payload (environment/suite
 *     plus up to 25 short test keys) is well under this.
 * @param retentionRunHistoryMaxAge (D4.1) a terminal run is eligible for full cleanup once its
 *     {@code finished_at} is older than this - enforced together with {@link
 *     #retentionRunHistoryMaxCount} as an either-bound trigger (see {@code RetentionService}),
 *     never both required at once.
 * @param retentionRunHistoryMaxCount (D4.1) a terminal run is eligible for full cleanup once its
 *     rank (newest-first, ties broken by {@code requested_at} then {@code run_id}) among terminal,
 *     not-yet-cleaned-up runs exceeds this count.
 * @param retentionArtifactMaxAge (D4.1) a terminal run's own artifact files (screenshots/traces/
 *     videos) are purged once its {@code finished_at} is older than this - measured from the run's
 *     own completion time, never from individual artifact ingestion timestamps, and always no
 *     larger than {@link #retentionRunHistoryMaxAge} (validated below) - otherwise the purge branch
 *     could never fire before full-run cleanup already deleted the run outright.
 * @param retentionCleanupInterval (D4.1) how often the background retention sweep ({@code
 *     RetentionService}) runs.
 * @param retentionRateLimit (D4.1 review round) per-admin (GitHub numeric id) limit on both {@code
 *     GET /api/v1/retention/preview} and {@code POST /api/v1/retention/run} - deliberately
 *     conservative, since a real sweep does real DB/filesystem work; without this, a valid or
 *     stolen admin session could trigger it as often as it likes. Each of the two routes is tracked
 *     as its own independent counter against this same threshold (see {@code
 *     AbuseRateLimitFilter}'s two separate {@code retention-preview}/{@code retention-run}
 *     surfaces), the same way {@code oauthAuthorizationRateLimit}/{@code oauthCallbackRateLimit}
 *     are two related but separately-tracked surfaces.
 * @param diskMinFreeBytes (D4.2) the floor that must remain free even after a newly-starting run
 *     consumes up to {@link #runMaxDiskBytes()} more - {@code DiskUsageService}'s submit/pre-launch
 *     guards reject work once usable space would drop below {@code diskMinFreeBytes +
 *     runMaxDiskBytes()}, not merely below {@code diskMinFreeBytes} itself.
 * @param artifactMaxBytes (D4.2) maximum size of a single artifact file (screenshot/trace/video) -
 *     enforced primarily at the producer (the main automation suite, a true pre-write cap for a
 *     screenshot, a post-finalization delete-and-reject for a trace) and re-checked here as a
 *     second, independent layer by {@code ArtifactManifestWriter}.
 * @param runMaxTotalArtifactBytes (D4.2) maximum total artifact bytes one run may accumulate,
 *     computed from the real artifacts directory's own contents (not an in-memory counter, which
 *     would not hold across the two independent OS processes {@code ArtifactManifestWriter} already
 *     supports) under its existing file lock.
 * @param manifestMaxBytes (D4.2) maximum size of one run's {@code manifest.jsonl} itself - enforced
 *     both at the producer ({@code ArtifactManifestWriter}, before appending a line that would
 *     exceed it) and the consumer ({@code ArtifactManifestReader}'s bounded read, which allocates a
 *     single in-memory buffer no larger than this value - see the additional ceiling validated
 *     below).
 * @param rawEventMaxBytes (D4.2) maximum size of one run's raw {@code <runId>.tests.jsonl} event
 *     stream - {@code RunnerEventJsonlWriter} stops writing on the first breach and records a
 *     distinct {@code .tests.overflow} marker instead of the normal completion marker, so {@code
 *     ListenerEventIngestor} can never mistake a truncated stream for a cleanly complete one.
 * @param managedScratchMaxBytes (D4.2) a disk-budget reservation for Gradle/JUnit report and
 *     temporary output this design does not itself enforce - a budget line, not an enforced quota,
 *     folded into {@link #runMaxDiskBytes()} so the availability guard doesn't undercount a run's
 *     real total footprint.
 * @param diskUsageRateLimit (D4.2) per-admin (GitHub numeric id) limit on {@code GET
 *     /api/v1/disk/usage} - a filesystem-tree walk plus a live Postgres size query is real work,
 *     the same reasoning {@link #retentionRateLimit} already applies to its own admin-only reads.
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
    RateLimitRule diskUsageRateLimit) {

  private static final long MANIFEST_MAX_BYTES_CEILING = 104_857_600L; // 100 MiB

  /**
   * Derived, never independently configured: the worst-case total disk one starting run can still
   * consume across every writer it touches (artifacts, process log, raw events, manifest, and a
   * reserve for unmanaged Gradle/JUnit scratch output) - see {@link #diskMinFreeBytes} for how the
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
