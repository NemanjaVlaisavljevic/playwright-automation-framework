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
 *     -Drunner.rawEventsDir}. Distinct from {@link #journalDir}: this is the listener's own,
 *     unprocessed test-event stream, not the runner service's canonical, cross-run-lifecycle event
 *     journal.
 * @param journalDir directory the runner service's own canonical event journal writes each run's
 *     {@code <runId>.events.jsonl}/{@code .events.complete} files into - a completely separate,
 *     service-owned timeline that also carries {@code RUN_*} lifecycle events the listener never
 *     produces.
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
 */
@ConfigurationProperties(prefix = "runner")
public record RunnerProperties(
    String repoRoot,
    Duration processTimeout,
    String rawEventsDir,
    String journalDir,
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
    Duration sseEmitterTimeout) {

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
    if (journalDir == null || journalDir.isBlank()) {
      throw new IllegalArgumentException("runner.journal-dir must not be blank");
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
  }
}
