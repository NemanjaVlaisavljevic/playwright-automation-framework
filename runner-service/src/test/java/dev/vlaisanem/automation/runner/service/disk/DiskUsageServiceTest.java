package dev.vlaisanem.automation.runner.service.disk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vlaisanem.automation.runner.service.config.RateLimitRule;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import dev.vlaisanem.automation.runner.service.disk.DiskUsageService.DiskUsageSnapshot;
import dev.vlaisanem.automation.runner.service.exception.DiskUsageUnavailableException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskUsageServiceTest {

  @Test
  void runnerDataBytesSumsRegularFilesAcrossAllThreeConfiguredRoots(@TempDir Path root)
      throws IOException {
    Path rawEvents = root.resolve("raw");
    Path logs = root.resolve("logs");
    Path artifacts = root.resolve("artifacts");
    DiskUsageService service = serviceFor(rawEvents, logs, artifacts);
    service.initializeStorage();

    Files.write(rawEvents.resolve("run-1.tests.jsonl"), new byte[100]);
    Files.write(logs.resolve("run-1.log"), new byte[50]);
    Path artifactSubdir = artifacts.resolve("run-1");
    Files.createDirectories(artifactSubdir);
    Files.write(artifactSubdir.resolve("failure.png"), new byte[25]);

    assertThat(service.runnerDataBytes()).isEqualTo(175L);
  }

  @Test
  void initializeStorageCreatesEveryConfiguredRootDirectory(@TempDir Path root) {
    Path rawEvents = root.resolve("raw");
    Path logs = root.resolve("logs");
    Path artifacts = root.resolve("artifacts");
    DiskUsageService service = serviceFor(rawEvents, logs, artifacts);

    service.initializeStorage();

    assertThat(rawEvents).isDirectory();
    assertThat(logs).isDirectory();
    assertThat(artifacts).isDirectory();
  }

  @Test
  void initializeStorageFailsClosedWhenTwoConfiguredRootsAreNested(@TempDir Path root) {
    Path rawEvents = root.resolve("raw");
    Path logs = root.resolve("logs");
    // artifactsDir nested inside rawEventsDir - would double-count in runnerDataBytes().
    Path artifacts = rawEvents.resolve("artifacts");
    DiskUsageService service = serviceFor(rawEvents, logs, artifacts);

    assertThatThrownBy(service::initializeStorage)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nested inside one another");
  }

  @Test
  void snapshotThrowsADiskUsageUnavailableExceptionWhenAConfiguredRootDoesNotExist(
      @TempDir Path root) {
    // Deliberately never calling initializeStorage() - a configured root that was never created
    // (or was removed after startup) must fail closed, not silently report space as available.
    DiskUsageService service =
        serviceFor(root.resolve("raw"), root.resolve("logs"), root.resolve("artifacts"));

    assertThatThrownBy(service::snapshot).isInstanceOf(DiskUsageUnavailableException.class);
  }

  @Test
  void belowThresholdAccountsForTheFullRunMaxDiskBudgetNotJustTheFloor(@TempDir Path root) {
    Path rawEvents = root.resolve("raw");
    Path logs = root.resolve("logs");
    Path artifacts = root.resolve("artifacts");
    DiskUsageService service = serviceFor(rawEvents, logs, artifacts);
    service.initializeStorage();

    // usableFreeBytes sits between diskMinFreeBytes alone and diskMinFreeBytes + runMaxDiskBytes -
    // a design that only checked against the bare floor would wrongly call this "available".
    DiskUsageSnapshot usage = new DiskUsageSnapshot(2_000_000L, 1_048_576L, 314_572_800L, null);

    assertThat(usage.belowThreshold()).isTrue();
  }

  private static DiskUsageService serviceFor(Path rawEvents, Path logs, Path artifacts) {
    RateLimitRule aRule = new RateLimitRule(5, Duration.ofMinutes(1));
    RunnerProperties properties =
        new RunnerProperties(
            ".",
            Duration.ofMinutes(10),
            rawEvents.toString(),
            logs.toString(),
            "src/test/resources/catalog/public-test-catalog.json",
            artifacts.toString(),
            1024 * 1024,
            Duration.ofSeconds(5),
            Duration.ofSeconds(1),
            1,
            Duration.ofMillis(150),
            Duration.ofSeconds(5),
            10_000,
            Duration.ofSeconds(15),
            Duration.ofMinutes(10),
            aRule,
            aRule,
            aRule,
            aRule,
            aRule,
            aRule,
            aRule,
            3,
            16384,
            Duration.ofDays(30),
            500,
            Duration.ofDays(14),
            Duration.ofHours(1),
            aRule,
            1_048_576L,
            26_214_400L,
            209_715_200L,
            2_097_152L,
            2_097_152L,
            104_857_600L,
            aRule,
            Duration.ofSeconds(60));
    return new DiskUsageService(properties, null);
  }
}
