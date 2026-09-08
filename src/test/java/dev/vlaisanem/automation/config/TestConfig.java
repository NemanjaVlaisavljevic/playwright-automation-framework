package dev.vlaisanem.automation.config;

import dev.vlaisanem.automation.runner.contract.RunnerExecutionIdentity;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

public record TestConfig(
    String baseUrl,
    BrowserName browser,
    boolean headless,
    Duration actionTimeout,
    Duration navigationTimeout,
    boolean tracing,
    boolean recordVideo,
    Path artifactsDirectory,
    String runId,
    String adminUsername,
    String adminPassword,
    String sharedTargetBaseUrl,
    boolean allowMutationAgainstSharedTarget,
    long artifactMaxBytes,
    long runMaxTotalArtifactBytes,
    long manifestMaxBytes,
    long traceCaptureMinFreeBytes,
    boolean allureAttachmentsEnabled) {

  private static final TestConfig INSTANCE = load();

  public TestConfig {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl must not be blank");
    }
    baseUrl = stripTrailingSlash(baseUrl);
    if (actionTimeout.isNegative() || actionTimeout.isZero()) {
      throw new IllegalArgumentException("actionTimeout must be positive");
    }
    if (navigationTimeout.isNegative() || navigationTimeout.isZero()) {
      throw new IllegalArgumentException("navigationTimeout must be positive");
    }
    if (runId == null || runId.isBlank()) {
      // Caught here, not left to surface only once ArtifactManifestWriter tries to use it - a
      // blank/missing runId would otherwise be reported as a generic "could not capture artifact"
      // warning, hiding a real configuration bug behind an unrelated-looking log line.
      throw new IllegalArgumentException("runId must not be blank");
    }
    if (sharedTargetBaseUrl == null || sharedTargetBaseUrl.isBlank()) {
      throw new IllegalArgumentException("sharedTargetBaseUrl must not be blank");
    }
    sharedTargetBaseUrl = stripTrailingSlash(sharedTargetBaseUrl);
    if (artifactMaxBytes <= 0) {
      throw new IllegalArgumentException("artifactMaxBytes must be positive");
    }
    if (runMaxTotalArtifactBytes <= 0) {
      throw new IllegalArgumentException("runMaxTotalArtifactBytes must be positive");
    }
    if (artifactMaxBytes > runMaxTotalArtifactBytes) {
      throw new IllegalArgumentException(
          "artifactMaxBytes must not exceed runMaxTotalArtifactBytes");
    }
    if (manifestMaxBytes <= 0) {
      throw new IllegalArgumentException("manifestMaxBytes must be positive");
    }
    if (traceCaptureMinFreeBytes <= 0) {
      throw new IllegalArgumentException("traceCaptureMinFreeBytes must be positive");
    }
  }

  /**
   * True when {@link #baseUrl()} points at the shared, non-isolated target ({@link
   * #sharedTargetBaseUrl()}) rather than a dedicated local/CI environment. Compares normalized
   * origins (scheme + host + effective port), not raw strings, so an equivalent URL spelling (e.g.
   * an explicit default port) can't slip past the guard this feeds.
   */
  public boolean targetsSharedEnvironment() {
    return normalizedOrigin(baseUrl).equals(normalizedOrigin(sharedTargetBaseUrl));
  }

  private static String normalizedOrigin(String url) {
    URI uri = URI.create(url);
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    int port = uri.getPort();
    if (port == -1) {
      port = defaultPortFor(scheme);
    }
    return scheme + "://" + host + ":" + port;
  }

  private static int defaultPortFor(String scheme) {
    return switch (scheme) {
      case "https" -> 443;
      case "http" -> 80;
      default -> -1;
    };
  }

  public static TestConfig current() {
    return INSTANCE;
  }

  private static TestConfig load() {
    return new TestConfig(
        setting("baseUrl", "BASE_URL", "https://automationintesting.online"),
        BrowserName.from(setting("browser", "BROWSER", "chromium")),
        booleanSetting("headless", "HEADLESS", true),
        Duration.ofMillis(longSetting("actionTimeoutMs", "ACTION_TIMEOUT_MS", 10_000)),
        Duration.ofMillis(longSetting("navigationTimeoutMs", "NAVIGATION_TIMEOUT_MS", 30_000)),
        booleanSetting("tracing", "TRACING", true),
        booleanSetting("recordVideo", "RECORD_VIDEO", false),
        Path.of(setting("artifactsDir", "ARTIFACTS_DIR", "build/artifacts")),
        // Resolved through the one shared, JVM-scoped identity both this class and
        // RunnerEventTestExecutionListener use - not this class's own independent setting() lookup
        // - so a manifest entry's runId always matches its run's RunnerEvents, however the JVM was
        // launched (a Gradle Test task, an IDE's own JUnit runner, a bare `java` invocation). See
        // RunnerExecutionIdentity's own Javadoc for why generating this fallback in a Gradle build
        // script closure instead would be strictly worse.
        RunnerExecutionIdentity.currentRunId(),
        setting("adminUsername", "ADMIN_USERNAME", "admin"),
        setting("adminPassword", "ADMIN_PASSWORD", "password"),
        setting(
            "sharedTargetBaseUrl", "SHARED_TARGET_BASE_URL", "https://automationintesting.online"),
        booleanSetting(
            "allowMutationAgainstSharedTarget", "ALLOW_MUTATION_AGAINST_SHARED_TARGET", false),
        // D4.2 - disk protection. Defaults match runner-service's own RunnerProperties defaults
        // (application.yml's D4.2 block) so a dashboard-launched run's producer-side limits agree
        // with the consumer-side limits it's checked against - runner-service threads its own
        // configured values down as env vars for every run it launches (see RunService), so these
        // defaults only apply to a standalone Gradle invocation it never launched.
        longSetting("artifactMaxBytes", "ARTIFACT_MAX_BYTES", 26_214_400L),
        longSetting("runMaxTotalArtifactBytes", "RUN_MAX_TOTAL_ARTIFACT_BYTES", 209_715_200L),
        longSetting("manifestMaxBytes", "MANIFEST_MAX_BYTES", 2_097_152L),
        // Default matches runner-service's own derived floor (RunnerProperties' default
        // diskMinFreeBytes 1 GiB + artifactMaxBytes 25 MiB) - see RunService's own Javadoc for why
        // this must never independently drift from the submit-time guard's own floor.
        longSetting("traceCaptureMinFreeBytes", "TRACE_CAPTURE_MIN_FREE_BYTES", 1_099_956_224L),
        booleanSetting("allureAttachmentsEnabled", "ALLURE_ATTACHMENTS_ENABLED", true));
  }

  private static String setting(String property, String environment, String fallback) {
    String systemValue = System.getProperty(property);
    if (systemValue != null && !systemValue.isBlank()) {
      return systemValue.trim();
    }
    String environmentValue = System.getenv(environment);
    return environmentValue == null || environmentValue.isBlank()
        ? fallback
        : environmentValue.trim();
  }

  private static boolean booleanSetting(String property, String environment, boolean fallback) {
    return Boolean.parseBoolean(setting(property, environment, Boolean.toString(fallback)));
  }

  private static long longSetting(String property, String environment, long fallback) {
    String value = setting(property, environment, Long.toString(fallback));
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          property + " must be a whole number, but was '" + value + "'", exception);
    }
  }

  private static String stripTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
