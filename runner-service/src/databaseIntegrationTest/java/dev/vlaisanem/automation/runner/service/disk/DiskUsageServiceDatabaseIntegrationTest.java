package dev.vlaisanem.automation.runner.service.disk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vlaisanem.automation.runner.service.config.RateLimitRule;
import dev.vlaisanem.automation.runner.service.config.RunnerProperties;
import java.nio.file.Path;
import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies {@link DiskUsageService#databaseBytes()} against a real Postgres. */
@Testcontainers
class DiskUsageServiceDatabaseIntegrationTest {

  @Container
  private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @TempDir private Path tempDir;

  private DiskUsageService diskUsageService;

  @BeforeEach
  void setUp() {
    String jdbcUrl = postgres.getJdbcUrl();
    Flyway.configure()
        .dataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword())
        .load()
        .migrate();

    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(jdbcUrl);
    dataSource.setUser(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

    RunnerProperties properties = testProperties(tempDir);
    diskUsageService = new DiskUsageService(properties, jdbcTemplate);
    diskUsageService.initializeStorage();
  }

  @Test
  void databaseBytesReturnsARealPositiveSizeFromTheLiveDatabase() {
    long size = diskUsageService.databaseBytes();

    // Even a freshly-migrated, empty schema occupies real disk - pg_database_size never reports 0.
    assertThat(size).isPositive();
  }

  private static RunnerProperties testProperties(Path tempDir) {
    RateLimitRule aRule = new RateLimitRule(5, Duration.ofMinutes(1));
    return new RunnerProperties(
        ".",
        Duration.ofSeconds(30),
        tempDir.resolve("raw").toString(),
        tempDir.resolve("logs").toString(),
        "src/test/resources/catalog/public-test-catalog.json",
        tempDir.resolve("artifacts").toString(),
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
  }
}
